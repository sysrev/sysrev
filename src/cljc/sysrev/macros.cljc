(ns sysrev.macros
  (:require [cljs.analyzer.api :as ana-api]
            [re-frame.core :refer [subscribe dispatch]]
            [secretary.core :refer [defroute]]
            [sysrev.shared.util :refer [map-values]]))

(defmacro with-mount-hook [on-mount]
  `(fn [content#]
     (reagent.core/create-class
      {:component-did-mount
       ~on-mount
       :reagent-render
       (fn [content#] content#)})))

(defmacro import-vars [[_quote ns]]
  `(do
     ~@(->>
        (ana-api/ns-interns ns)
        (remove (comp :macro second))
        (map (fn [[k# _]]
               `(def ~(symbol k#) ~(symbol (name ns) (name k#))))))))

(defmacro with-loader
  "Wraps a UI component to define required data and delay rendering until
  data has been loaded."
  [reqs options content-form]
  `(let [reqs# ~reqs
         options# ~options
         dimmer# (:dimmer options#)
         min-height# (:min-height options#)
         class# (:class options#)
         loading# (some #(deref (subscribe [:loading? %])) reqs#)
         have-data# (every? #(deref (subscribe [:have? %])) reqs#)
         content-form# ~content-form
         dimmer-active# (and dimmer# (or loading# (and class# (not have-data#))))]
     (doseq [item# reqs#]
       (dispatch [:require item#]))
     [:div {:style (when (and (not have-data#) min-height#)
                     {:min-height min-height#})
            :class (cond class# class#

                         (and (not have-data#) dimmer#)
                         "ui segment dimmer-segment")}
      (when (or dimmer-active# (= dimmer# :fixed))
        [:div.ui.inverted.dimmer
         {:class (when dimmer-active# "active")}
         [:div.ui.loader]])
      (cond (and dimmer-active# have-data#)
            [:div {:style {:visibility "hidden"}}
             (if (seq? content-form#)
               (doall content-form#)
               content-form#)]

            have-data#
            (if (seq? content-form#)
              (doall content-form#)
              content-form#)

            :else [:div])]))

(defmacro sr-defroute
  [name uri params & body]
  `(defroute ~name ~uri ~params
     (let [bodyfn# #(do ~@body)]
       (if-let [article-id# @(subscribe [:review/editing-id])]
         (let [user-id# @(subscribe [:self/user-id])
               article-values# (->> @(subscribe [:article/labels article-id# user-id#])
                                    (map-values :answer))
               active-values# @(subscribe [:review/active-labels article-id#])
               user-status# @(subscribe [:article/user-status article-id# user-id#])
               unconfirmed?# (or (= user-status# :unconfirmed)
                                 (= user-status# :none))
               resolving?# @(subscribe [:review/resolving?])
               article-loading?# @(subscribe [:loading? [:article article-id#]])
               send-labels?# (and unconfirmed?#
                                  (not resolving?#)
                                  (not article-loading?#)
                                  (not= active-values# article-values#))
               sent-notes# (sysrev.events.notes/sync-article-notes article-id#)]
           (do (when send-labels?#
                 (dispatch [:action [:review/send-labels
                                     {:article-id article-id#
                                      :label-values active-values#
                                      :confirm? false
                                      :resolve? false
                                      :change? false}]]))
               (if (or send-labels?# (not-empty sent-notes#))
                 (js/setTimeout bodyfn# 125)
                 (bodyfn#))
               (dispatch [:review/reset-saving])))
         (bodyfn#)))))
