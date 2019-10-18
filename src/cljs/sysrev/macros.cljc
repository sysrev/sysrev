(ns sysrev.macros
  (:require [clojure.string :as str]
            [cljs.analyzer.api :as ana-api]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [re-frame.db :refer [app-db]]
            [secretary.core :refer [defroute]]
            [sysrev.loading]
            #?(:cljs [sysrev.state.ui])
            #?(:cljs [sysrev.util])
            [sysrev.shared.util :refer [map-values parse-integer filter-values ensure-pred]]))

(defmacro with-mount-hook [on-mount]
  `(fn [content#]
     (reagent.core/create-class
      {:component-did-mount ~on-mount
       :reagent-render (fn [content#] content#)})))

(defmacro import-vars [[_quote ns]]
  `(do ~@(->> (ana-api/ns-publics ns)
              (remove (comp :macro second))
              (map (fn [[k# _]] `(def ~(symbol k#)
                                   ~(symbol (name ns) (name k#))))))))

(defmacro with-loader
  "Wraps a UI component to define required data and delay rendering until
  data has been loaded."
  [reqs options content-form]
  `(let [reqs# (->> ~reqs (remove nil?) vec)
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
                                project-id {:article-id article-id
                                            :label-values active-values
                                            :confirm? false :resolve? false :change? false}]]))
          (when sync-notes?
            (dispatch [:review/sync-article-notes article-id ui-notes article-notes]))
          (if (or send-labels? sync-notes?)
            #?(:cljs (js/setTimeout route-fn 75)
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
       (let [clear-text# true]
         (letfn [(route-fn# []
                   (go-route-sync-data
                    #(do (dispatch [:set-active-panel nil])
                         (dispatch [:set-active-project-url nil])
                         (when clear-text# (sysrev.util/clear-text-selection))
                         ~@body
                         (when clear-text# (sysrev.util/clear-text-selection-soon)))))
                 (route-fn-when-ready# []
                   (if (sysrev.loading/ajax-status-inactive?)
                     (route-fn#)
                     (js/setTimeout route-fn-when-ready# 20)))]
           #_ (route-fn#)
           (route-fn-when-ready#)
           #_ (js/setTimeout route-fn-when-ready# 20))))))

(defn lookup-project-url [url-id]
  @(subscribe [:lookup-project-url url-id]))

(defmacro sr-defroute-project--impl
  [owner-key name uri suburi params & body]
  `(defroute ~name ~uri ~params
     (let [clear-text# true
           project-url-id# ~(if owner-key (second params) (first params))
           owner-url-id# ~(when (and owner-key (first params))
                            {owner-key (first params)})
           url-id# [project-url-id# owner-url-id#]]
       (letfn [(body-fn# []
                 (when clear-text# (sysrev.util/clear-text-selection))
                 ~@body
                 (when clear-text# (sysrev.util/clear-text-selection-soon)))
               (route-fn# []
                 (let [cur-id# @(subscribe [:active-project-url])
                       full-id# @(subscribe [:lookup-project-url url-id#])
                       project-id# (or (ensure-pred #(= :loading %) full-id#)
                                       (some->> (ensure-pred map? full-id#)
                                                :project-id
                                                (ensure-pred integer?))
                                       :not-found)]
                   (dispatch [:set-active-project-url url-id#])
                   (cond
                     (= project-id# :not-found)
                     (do (js/console.log "got :not-found")
                         (body-fn#))
                     ;; If running lookup on project-id value for url-id,
                     ;; wait until lookup completes before running route
                     ;; body function.
                     (= project-id# :loading)
                     (do #_ (js/console.log (str "loading for " (pr-str url-id#)))
                         (dispatch [:data/after-load
                                    [:lookup-project-url url-id#]
                                    [:project-url-loader ~uri]
                                    body-fn#]))
                     ;; If url-id changed, need to give time for
                     ;; :set-active-project-url event chain to finish so
                     ;; :active-project-id is updated before running route
                     ;; body function (dispatch is asynchronous).
                     (not= url-id# cur-id#)
                     (do #_ (js/console.log (str "url-id changed to " (pr-str url-id#)))
                         (js/setTimeout body-fn# 30)
                         #_ (body-fn#))
                     ;; Otherwise run route body function immediately.
                     :else (body-fn#))))
               (route-fn-when-ready# []
                 (if (sysrev.loading/ajax-status-inactive?)
                   (go-route-sync-data route-fn#)
                   (js/setTimeout route-fn-when-ready# 20)))]
         #_ (route-fn-when-ready#)
         (go-route-sync-data route-fn#)
         #_ (js/setTimeout route-fn-when-ready# 20)))))

(defmacro sr-defroute-project
  [name suburi params & body]
  (assert (or (empty? suburi) (str/starts-with? suburi "/"))
          (str "sr-defroute-project: suburi must be empty (root) or begin with \"/\"\n"
               "suburi = " (pr-str suburi)))
  (assert (= (first params) 'project-id)
          (str "sr-defroute-project: params must start with project-id\n"
               "params = " (pr-str params)))
  (let [suffix-name (fn [suffix] (-> name clojure.core/name (str "__" suffix) symbol))
        body-full `(let [~(first params) @(subscribe [:active-project-id])]
                     ~@body)]
    `(do (sr-defroute-project--impl nil
                                    ~(suffix-name "legacy")
                                    ~(str "/p/:project-id" suburi)
                                    ~suburi
                                    ~params
                                    ~body-full)
         (sr-defroute-project--impl :user-url-id
                                    ~(suffix-name "user")
                                    ~(str "/u/:owner-id/p/:project-id" suburi)
                                    ~suburi
                                    ~(vec (concat '(owner-id) params))
                                    ~body-full)
         (sr-defroute-project--impl :org-url-id
                                    ~(suffix-name "org")
                                    ~(str "/o/:owner-id/p/:project-id" suburi)
                                    ~suburi
                                    ~(vec (concat '(owner-id) params))
                                    ~body-full))))

(defmacro setup-panel-state
  "Creates standard definitions at the top of a UI panel namespace.

  `panel-var` and `path` are required, other args are optional.

  `path` must be a vector of keywords identifying the panel.

  `panel-var` must be a symbol (usually `panel`) providing a var name
  to define as the `path` value.

  `state-var` (optional) should be a symbol (usually `state`)
  providing a var name to define as a reagent cursor into the panel
  state within the re-frame app-db.

  `get-fn` and `set-fn` (optional) should be symbols that will be
  used to define re-frame compatible functions (operating on the
  app-db map value) for interacting with panel state.

  `get-sub` and `set-event` (optional) should be keywords (usually
  namespace-local, prefixed with `::`) that will be used to define a
  re-frame `reg-sub` and `reg-event-db` for interacting with panel
  state."
  [panel-var path & [{:keys [state-var get-fn set-fn get-sub set-event]}]]
  ;; TODO: check that all these argument values are valid
  (assert (and (vector? path) (every? keyword? path) (not-empty path)))
  (assert (symbol? panel-var))
  (assert (or (nil? state-var) (symbol? state-var)))
  (assert (or (nil? get-fn) (symbol? get-fn)))
  (assert (or (nil? set-fn) (symbol? set-fn)))
  (assert (or (nil? get-sub) (keyword? get-sub)))
  (assert (or (nil? set-event) (keyword? set-event)))
  `(do
     ;; define var with panel key vector
     (def ~panel-var ~path)
     ;; define cursor to provide direct access to panel state map
     ~(when state-var
        `(defonce ~state-var (r/cursor app-db [:state :panels ~panel-var])))
     ;; define function for reading panel state from db value
     ~(when get-fn
        `(defn ~get-fn [db# & [path#]]
           (sysrev.state.ui/get-panel-field db# path# ~panel-var)))
     ;; define function for updating db value to set panel state
     ~(when set-fn
        `(defn ~set-fn [db# path# val#]
           (sysrev.state.ui/set-panel-field db# path# val# ~panel-var)))
     ;; define re-frame sub for reading panel state.
     ;; behavior should be equivalent to :panel-field.
     ~(when get-sub
        `(reg-sub ~get-sub (fn [db# [_ path#]]
                             (~get-fn db# path#))))
     ;; define re-frame event for setting panel state.
     ;; behavior should be equivalent to :set-panel-field.
     ~(when set-event
        `(reg-event-db ~set-event (fn [db# [_ path# val#]]
                                    (~set-fn db# path# val#))))))
