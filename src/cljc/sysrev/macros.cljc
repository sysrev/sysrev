(ns sysrev.macros
  (:require [cljs.analyzer.api :as ana-api]
            [re-frame.core :refer [subscribe dispatch]]
            [secretary.core :refer [defroute]]
            [sysrev.shared.util :refer [map-values parse-integer]]
            [clojure.string :as str]))

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
        (ana-api/ns-publics ns)
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
         force-dimmer# (:force-dimmer options#)
         min-height# (:min-height options#)
         class# (:class options#)
         require# (get options# :require true)
         loading# (some #(deref (subscribe [:loading? %])) reqs#)
         have-data# (every? #(deref (subscribe [:have? %])) reqs#)
         content-form# ~content-form
         dimmer-active# (and dimmer# (or loading#
                                         (and class# (not have-data#))
                                         force-dimmer#))]
     (when require#
       (doseq [item# reqs#]
         (dispatch [:require item#])))
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

(defn go-route-sync-data [route-fn]
  (if-let [article-id @(subscribe [:review/editing-id])]
    (let [project-id @(subscribe [:active-project-id])
          user-id @(subscribe [:self/user-id])
          article-values (->> @(subscribe [:article/labels article-id user-id])
                              (map-values :answer))
          active-values @(subscribe [:review/active-labels article-id])
          user-status @(subscribe [:article/user-status article-id user-id])
          unconfirmed? (or (= user-status :unconfirmed)
                           (= user-status :none))
          resolving? @(subscribe [:review/resolving?])
          article-loading? @(subscribe [:loading? [:article project-id article-id]])
          send-labels? (and unconfirmed?
                            (not resolving?)
                            (not article-loading?)
                            (not= active-values article-values))
          sync-notes? (not @(subscribe [:review/all-notes-synced? article-id]))
          ui-notes @(subscribe [:review/ui-notes article-id])
          article-notes @(subscribe [:article/notes article-id user-id])]
      (do (when send-labels?
            (dispatch [:action [:review/send-labels
                                project-id
                                {:article-id article-id
                                 :label-values active-values
                                 :confirm? false
                                 :resolve? false
                                 :change? false}]]))
          (when sync-notes?
            (dispatch [:review/sync-article-notes
                       article-id ui-notes article-notes]))
          (if (or send-labels? sync-notes?)
            #?(:cljs (js/setTimeout route-fn 125)
               :clj (route-fn))
            (route-fn))
          (dispatch [:review/reset-saving])))
    (route-fn)))

(defmacro sr-defroute
  [name uri params & body]
  `(defroute ~name ~uri ~params
     (let [route-fn# #(do
                        (dispatch [:self/set-active-project nil])
                        ~@body)]
       (go-route-sync-data route-fn#))))

(defmacro sr-defroute-project
  [name suburi params & body]
  (assert (or (empty? suburi)
              (str/starts-with? suburi "/"))
          (str "sr-defroute-project: suburi must be empty (root) or begin with \"/\"\n"
               "suburi = " (pr-str suburi)))
  (assert (= (first params) 'project-id)
          (str "sr-defroute-project: params must include 'project-id\n"
               "params = " (pr-str params)))
  (let [uri (str "/project/:project-id" suburi)]
    `(defroute ~name ~uri ~params
       (let [route-fn#
             #(let [project-id# (parse-integer ~(first params))]
                (dispatch [:self/set-active-project project-id#])
                ~@body)]
         (go-route-sync-data route-fn#)))))
