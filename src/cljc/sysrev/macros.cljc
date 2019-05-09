(ns sysrev.macros
  (:require [clojure.string :as str]
            [cljs.analyzer.api :as ana-api]
            [re-frame.core :refer [subscribe dispatch]]
            [secretary.core :refer [defroute]]
            [sysrev.loading]
            [sysrev.shared.util :refer [map-values parse-integer filter-values]]))

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
         loading# (some #(sysrev.loading/item-loading? %) reqs#)
         have-data# (every? #(deref (subscribe [:have? %])) reqs#)
         content-form# ~content-form
         dimmer-active# (and dimmer# (or loading#
                                         (and class# (not have-data#))
                                         force-dimmer#))]
     (when require#
       (doseq [item# reqs#]
         (dispatch [:require item#])))
     (if (and (empty? options#) have-data# (not dimmer-active#))
       (if (seq? content-form#)
         [:div (doall content-form#)]
         content-form#)
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

              :else [:div])])))

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
          article-loading? (sysrev.loading/item-loading? [:article project-id article-id])
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

(defmacro defroute-app-id
  [name uri params app-id & body]
  `(when (= ~app-id @(subscribe [:app-id]))
     (defroute ~name ~uri ~params
       ~@body)))

(defmacro sr-defroute
  [name uri params & body]
  `(when (= :main @(subscribe [:app-id]))
     (defroute ~name ~uri ~params
       (let [clear-text# true
             use-timeout# false
             route-fn#
             (fn []
               (go-route-sync-data
                #(do (dispatch [:set-active-panel nil])
                     (dispatch [:set-active-project-url nil])
                     (when clear-text#
                       (sysrev.util/clear-text-selection))
                     ~@body
                     (when clear-text#
                       (sysrev.util/clear-text-selection-soon)))))]
         (if use-timeout#
           (js/setTimeout route-fn# 20)
           (route-fn#))))))

(defn lookup-project-url-id [url-id]
  (let [[project-url-id {:keys [user-url-id org-url-id] :as owner}] url-id]
    ;; TODO: update for owner ids
    (if (and (parse-integer project-url-id) (nil? owner))
      (parse-integer project-url-id)
      @(subscribe [:project-url-id url-id]))))

(defmacro sr-defroute-project--impl
  [owner-key name uri params & body]
  `(defroute ~name ~uri ~params
     (let [clear-text# true
           use-timeout# false
           project-url-id# ~(if owner-key (second params) (first params))
           ;; _# (println "params = " (pr-str ~params))
           owner-url-id# ~(when owner-key (some->> (first params) (hash-map owner-key)))
           url-id# [project-url-id# owner-url-id#]
           body-fn# (fn []
                      (when clear-text# (sysrev.util/clear-text-selection))
                      ~@body
                      (when clear-text# (sysrev.util/clear-text-selection-soon)))
           route-fn# #(let [cur-id# @(subscribe [:active-project-url])]
                        ;; (println "sr-defroute: url-id = " (pr-str url-id#))
                        (dispatch [:set-active-project-url url-id#])
                        (let [project-id# (lookup-project-url-id url-id#)]
                          (println (str "running " (clojure.core/name '~name) ": "
                                        (pr-str {:project-id project-id#})))
                          (cond
                            ;; If running lookup on project-id value for url-id,
                            ;; wait until lookup completes before running route
                            ;; body function.
                            (= project-id# :not-found)
                            (dispatch [:data/after-load
                                       [:project-url-id url-id#]
                                       [:project-url-loader ~uri]
                                       body-fn#])
                            ;; If url-id changed, need to give time for
                            ;; :set-active-project-url event chain to finish so
                            ;; :active-project-id is updated before running route
                            ;; body function (dispatch is asynchronous).
                            (not= url-id# cur-id#)
                            (js/setTimeout body-fn# 50)
                            ;; Otherwise run route body function immediately.
                            :else (body-fn#))))]
       (if use-timeout#
         (js/setTimeout #(go-route-sync-data route-fn#) 20)
         (go-route-sync-data route-fn#)))))

(defmacro sr-defroute-project
  [name suburi params & body]
  (assert (or (empty? suburi) (str/starts-with? suburi "/"))
          (str "sr-defroute-project: suburi must be empty (root) or begin with \"/\"\n"
               "suburi = " (pr-str suburi)))
  (assert (= (first params) 'project-id)
          (str "sr-defroute-project: params must start with project-id\n"
               "params = " (pr-str params)))
  (let [suffix-name (fn [suffix] (-> name clojure.core/name (str "__" suffix) symbol))]
    `(do (sr-defroute-project--impl nil
                                    ~(suffix-name "legacy")
                                    ~(str "/p/:project-id" suburi)
                                    ~params
                                    ~@body)
         (sr-defroute-project--impl :user-url-id
                                    ~(suffix-name "user")
                                    ~(str "/u/:owner-id/p/:project-id" suburi)
                                    ~(vec (concat '(owner-id) params))
                                    ~@body)
         (sr-defroute-project--impl :org-url-id
                                    ~(suffix-name "org")
                                    ~(str "/org/:owner-id/p/:project-id" suburi)
                                    ~(vec (concat '(owner-id) params))
                                    ~@body))))
