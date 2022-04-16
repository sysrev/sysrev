(ns sysrev.macros
  (:require [clojure.string :as str]
            [cljs.analyzer.api :as ana-api]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [re-frame.db :refer [app-db]]
            [secretary.core :refer [defroute]]
            [sysrev.loading]
            #?@(:cljs [[sysrev.state.ui]
                       [sysrev.views.base]
                       [sysrev.data.core]])
            [sysrev.util :refer [when-test]]))

(defmacro with-mount-hook [on-mount]
  `(fn [content#]
     (reagent.core/create-class
      {:component-did-mount ~on-mount
       :reagent-render (fn [content#] content#)})))

(defmacro with-mount-body [& on-mount-body]
  `(with-mount-hook (fn [& _#] ~@on-mount-body)))

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
         loading# (sysrev.data.core/loading? (set reqs#))
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

#_[project-id @(subscribe [:active-project-id])
   article-values (->> @(subscribe [:article/labels article-id user-id])
                       (medley/map-vals :answer))
   active-values @(subscribe [:review/active-labels article-id])
   user-status @(subscribe [:article/user-status article-id user-id])
   unconfirmed? (or (= user-status :unconfirmed)
                    (= user-status :none))
   resolving? @(subscribe [:review/resolving?])
   article-loading? (sysrev.loading/data-loading? [:article project-id article-id])
   send-labels? (and unconfirmed?
                     (not resolving?)
                     (not article-loading?)
                     (not= active-values article-values))]
;; not sure this should occur here anymore, labels should
;; be manually saved and this causes errors in the console
#_(when send-labels?
    (dispatch [:action [:review/send-labels
                        project-id {:article-id article-id
                                    :label-values active-values
                                    :confirm? false :resolve? false :change? false}]]))

(defn go-route-sync-data [route-fn]
  (if-let [article-id @(subscribe [:review/editing-id])]
    (let [user-id @(subscribe [:self/user-id])
          sync-notes? (not @(subscribe [:review/all-notes-synced? article-id]))
          ui-notes @(subscribe [:review/ui-notes article-id])
          article-notes @(subscribe [:article/notes article-id user-id])]
      (when sync-notes?
        (dispatch [:review/sync-article-notes article-id ui-notes article-notes]))
      (if sync-notes?
        #?(:cljs (js/setTimeout route-fn 50)
           :clj (route-fn))
        (route-fn))
      (dispatch [:review/reset-saving]))
    (route-fn)))

(defmacro sr-defroute
  [name uri params & body]
  `(defroute ~@(when name [name]) ~uri ~params
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
                   (js/setTimeout route-fn-when-ready# 10)))]
         (route-fn-when-ready#)
         #_ (route-fn#)
         #_ (js/setTimeout route-fn-when-ready# 10)))))

(defmacro sr-defroute-project--impl
  [owner-key name uri _suburi params & body]
  `(defroute ~@(when name [name]) ~uri ~params
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
                       project-id# (or (when-test #(= :loading %) full-id#)
                                       (some->> (when-test map? full-id#)
                                                :project-id
                                                (when-test integer?))
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
                         (js/setTimeout body-fn# 10)
                         #_ (body-fn#))
                     ;; Otherwise run route body function immediately.
                     :else (body-fn#))))
               (route-fn-when-ready# []
                 (if (sysrev.loading/ajax-status-inactive?)
                   (go-route-sync-data route-fn#)
                   (js/setTimeout route-fn-when-ready# 10)))]
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

  `state` (optional) should be a symbol (usually literal `state`)
  providing a var name to define as a reagent cursor into the panel
  state within the re-frame app-db.

  `get` should be a vector of [`get-fn` `get-sub`].

  `get-fn` provides a symbol to define a re-frame compatible function,
  operating on the app-db map value, for reading panel state.

  `get-sub` provides a keyword (usually `::get`) to define a
  re-frame `reg-sub` for reading panel state.

  `set` should be a vector of [`set-fn` `set-event`].

  `set-fn` provides a symbol to define a re-frame compatible function,
  operating on the app-db map value, for changing panel state.

  `set-event` provides a keyword (usually `::set`) to define a
  re-frame `reg-event-db` for changing panel state."
  [panel-var path & {:keys [state get set]}]
  (let [[get-fn get-sub] get
        [set-fn set-event] set]
    ;; TODO: check that all these argument values are valid
    (assert (and (vector? path) (every? keyword? path) (not-empty path)))
    (assert (symbol? panel-var))
    (assert (or (nil? state) (symbol? state)))
    (assert (or (nil? get-fn) (symbol? get-fn)))
    (assert (or (nil? set-fn) (symbol? set-fn)))
    (assert (or (nil? get-sub) (keyword? get-sub)))
    (assert (or (nil? set-event) (keyword? set-event)))
    `(do
       ;; define var with panel key vector
       (def ~panel-var ~path)
       ;; define cursor to provide direct access to panel state map
       ~(when state
          `(defonce ~state (r/cursor app-db [:state :panels ~panel-var])))
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
          `(reg-sub ~get-sub (fn [db# [_# path#]]
                               (~get-fn db# path#))))
       ;; define re-frame event for setting panel state.
       ;; behavior should be equivalent to :set-panel-field.
       ~(when set-event
          `(reg-event-db ~set-event (fn [db# [_# path# val#]]
                                      (~set-fn db# path# val#)))))))

(defmacro def-panel [& {:keys [project? uri params name on-route
                               panel content require-login logged-out-content]
                        :or {params [] require-login false}}]
  (assert (or (nil? name) (symbol? name)) "name argument should be a symbol")
  (assert (some? uri) "uri argument is required")
  (when (some? panel)
    (assert (some? content) "content argument is required with panel"))
  (when (or content logged-out-content)
    (assert (some? panel) "panel must be provided with content"))
  (when (and (nil? content) (nil? logged-out-content))
    (assert (nil? panel) "panel should not be provided without content"))
  `(list ~(if project?
            `(sr-defroute-project ~name ~uri ~params ~on-route)
            `(sr-defroute ~name ~uri ~params ~on-route))
         ~(when (some? panel)
            `(assert (and (vector? ~panel) (every? keyword? ~panel))
                     "panel must be a vector of keywords"))
         ~(when (some? panel)
            `(defmethod sysrev.views.base/panel-content ~panel []
               ~(if (= 'fn (first content))
                  content
                  `(fn [_child#] ~content))))
         ~(when (some? panel)
            (let [logged-out-content (if require-login
                                       `(sysrev.views.base/logged-out-content :logged-out)
                                       logged-out-content)]
              (when logged-out-content
                `(defmethod sysrev.views.base/logged-out-content ~panel []
                   ~logged-out-content))))))
