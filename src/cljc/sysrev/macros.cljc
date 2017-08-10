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
         loading# (some #(deref (subscribe [:loading? %])) reqs#)
         have-data# (every? #(deref (subscribe [:have? %])) reqs#)
         content# (fn [] [:div (when (and dimmer# loading#)
                                 {:style {:visibility "hidden"}})
                          ~content-form])]
     (doseq [item# reqs#]
       (dispatch [:require item#]))
     [:div (when (and (not have-data#) dimmer# min-height#)
             {:style {:min-height min-height#}})
      (when dimmer#
        [:div.ui.inverted.dimmer
         {:class (if loading# "active" "")}
         [:div.ui.loader]])
      (if have-data# [content#] [:div])]))

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
               confirmed?# (= user-status# :confirmed)
               send-labels?# (and (not confirmed?#)
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
                 (bodyfn#))))
         (bodyfn#)))))
