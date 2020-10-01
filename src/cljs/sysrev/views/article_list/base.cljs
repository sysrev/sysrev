(ns sysrev.views.article-list.base
  (:require [clojure.string :as str]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx reg-fx trim-v]]
            [sysrev.base :refer [active-route]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [active-panel]]
            [sysrev.state.ui :as ui-state]
            [sysrev.util :as util :refer [in? dissoc-in parse-integer]]))

(def view :article-list)

(def default-options
  {:display {:expand-filters true
             :show-inclusion true
             :self-only false
             :show-labels false
             :show-notes false
             :show-unconfirmed false}
   :sort-by :content-updated
   :sort-dir :desc})

(defn get-display-count []
  (if (util/mobile?) 10 15))

(reg-sub ::panel
         :<- [:active-panel]
         (fn [active-panel [_ context]]
           (or (:panel context) active-panel)))

(defn- get-panel [db context]
  (or (:panel context) (active-panel db)))

;; Adding this `:read-cache?` flag to the `context` value causes `get-state` and
;; the `::get` subscription to prefix all state lookups with `:ready`, causing
;; all values to be read from the latest state for which all data was loaded.
;;
;; The `:ready` submap is updated continuously by `update-ready-state`.
(defn cached [context] (assoc context :read-cache? true))
(defn no-cache [context] (dissoc context :read-cache?))

(defn get-state [db context & [path]]
  (let [panel (get-panel db context)
        rstate #(ui-state/get-view-field db view [:ready] panel)
        path (as-> (or path []) path
               (if (and (:read-cache? context)
                        (not= path [:ready])
                        (not-empty (rstate)))
                 (vec (concat [:ready] path))
                 path))]
    (ui-state/get-view-field db view path panel)))

(reg-sub-raw ::get
             (fn [_ [_ context path]]
               (reaction
                (let [panel @(subscribe [::panel context])
                      path (as-> (or path []) path
                             (if (and (:read-cache? context)
                                      (not= path [:ready])
                                      (not-empty @(subscribe [::ready-state])))
                               (vec (concat [:ready] path))
                               path))]
                  @(subscribe [:view-field view path panel])))))

(defn set-state [db context path value]
  (let [panel (get-panel db context)
        path (or path [])]
    (ui-state/set-view-field db view path value panel)))

(reg-event-db ::set [trim-v]
              (fn [db [context path value]]
                (set-state db context path value)))

(defn active-sort-by [state context]
  (if (nil? (:sort-by state))
    (if (-> context :defaults :sort-by)
      (-> context :defaults :sort-by)
      (-> default-options :sort-by))
    (:sort-by state)))

(reg-sub ::sort-by
         (fn [[_ context]] (subscribe [::get context]))
         (fn [state [_ context]] (active-sort-by state context)))

(defn active-sort-dir [state context]
  (if (nil? (:sort-dir state))
    (if (-> context :defaults :sort-dir)
      (-> context :defaults :sort-dir)
      (-> default-options :sort-dir))
    (:sort-dir state)))

(reg-sub ::sort-dir
         (fn [[_ context]] (subscribe [::get context]))
         (fn [state [_ context]] (active-sort-dir state context)))

(reg-sub ::display-offset
         (fn [[_ context]] (subscribe [::get context]))
         #(or (:display-offset %) 0))

(reg-sub ::active-article
         (fn [[_ context]] (subscribe [::get context]))
         #(:active-article %))

(reg-event-fx ::set-active-article [trim-v]
              (fn [{:keys [db]} [context article-id]]
                {:db (set-state db context [:active-article] article-id)}))

(defn get-base-uri [context & [article-id]]
  (let [{:keys [base-uri article-base-uri]} context]
    (assert (string? base-uri))
    (assert (string? article-base-uri))
    (if article-id
      (str article-base-uri "/" article-id)
      base-uri)))

(defn- get-display-options-impl [state context & [key defaults-only?]]
  (let [state-options (-> state :display)
        context-defaults (-> context :defaults :display)
        display-defaults (-> default-options :display)]
    (cond-> (merge display-defaults context-defaults
                   (if defaults-only? {} state-options))
      key (get key))))
;;
(defn get-display-options [db context & [key defaults-only?]]
  (get-display-options-impl
   (get-state db context) context key defaults-only?))
;;
(reg-sub ::display-options
         (fn [[_ context _ _]] (subscribe [::get context]))
         (fn [state [_ context key defaults-only?]]
           (get-display-options-impl state context key defaults-only?)))

(reg-event-db ::set-display-option [trim-v]
              (fn [db [context key value]]
                (let [state (get-state db context)]
                  (if (nil? value)
                    (set-state db context nil (dissoc-in state [:display key]))
                    (set-state db context [:display key] value)))))

(defn- filter-to-json [entry]
  (let [[[k v]] (vec entry)
        convert (fn [m field f]
                  (if (contains? m field)
                    (update m field #(some-> % f))
                    m))]
    {k (->> (cond-> (convert v :label-id str)
              (= k :consensus) (convert :status name))
            (util/filter-values (comp not nil?)))}))

(defn- filter-from-json [entry]
  (let [[[k v]] (vec entry)
        convert (fn [m field f]
                  (cond-> m (contains? m field) (update field #(some-> % f))))
        convert-boolean (fn [m field]
                          (convert m field
                                   #(get {"true" true "false" false "any" nil "null" nil}
                                         % %)))]
    {k (cond-> (-> (convert v :label-id util/to-uuid)
                   (convert :content keyword)
                   (convert-boolean :confirmed)
                   (convert-boolean :inclusion))
         (= k :consensus)   (convert :status keyword)
         (= k :prediction)  (convert :direction keyword))}))

(defn- active-filters-impl [state context & [key]]
  (as-> (or (-> state :filters not-empty)
            (-> context :defaults :filters not-empty)) filters
    (if (nil? key)
      (vec filters)
      (->> filters (filterv #(in? (keys %) key))))))
;;
(defn get-active-filters [db context & [key]]
  (active-filters-impl (get-state db context) context key))
;;
(reg-sub ::filters
         (fn [[_ context & _]] (subscribe [::get context]))
         (fn [state [_ context & [key]]]
           (active-filters-impl state context key)))

(reg-event-db ::reset-all [trim-v]
              (fn [db [context]]
                (-> (set-state db context [:filters] nil)
                    (set-state context [:inputs :filters] nil)
                    (set-state context [:display-offset] nil)
                    (set-state context [:display] nil)
                    (set-state context [:sort-by] nil)
                    (set-state context [:sort-dir] nil))))

(defn- get-url-params-impl [db context]
  (let [{:keys [display-offset active-article text-search]
         :as state} (get-state db context)
        sort-by (active-sort-by state context)
        sort-dir (active-sort-dir state context)
        filters (->> (get-active-filters db context) (mapv filter-to-json))
        display (get-display-options db context)
        display-defaults (get-display-options db context nil true)
        display-changes (->> (keys display)
                             (filter #(not= (boolean (get display %))
                                            (boolean (get display-defaults %))))
                             (select-keys display))]
    (cond-> []
      display-offset              (conj [:offset display-offset])
      active-article              (conj [:show-article active-article])
      sort-by                     (conj [:sort-by (name sort-by)])
      sort-dir                    (conj [:sort-dir (name sort-dir)])
      (not-empty text-search)     (conj [:text-search text-search])
      (not-empty display-changes) (conj [:display (util/write-json display-changes)])
      (not-empty filters)         (conj [:filters (util/write-json filters)]))))
;;
(defn- get-url-params [db context]
  (get-url-params-impl db context))

(defn get-params-from-url []
  (let [{:keys [filters text-search display offset show-article sort-by sort-dir]}
        (util/get-url-params)]
    (cond-> {}
      (string? offset)        (assoc :offset (parse-integer offset))
      (string? text-search)   (assoc :text-search text-search)
      (string? sort-by)       (assoc :sort-by (keyword sort-by))
      (string? sort-dir)      (assoc :sort-dir (keyword sort-dir))
      (string? show-article)  (assoc :show-article (parse-integer show-article))
      (string? filters)       (assoc :filters (->> filters util/read-json (mapv filter-from-json)))
      (string? display)       (assoc :display (util/read-json display)))))

(defn- get-nav-url [db context & [article-id]]
  (nav/make-url (get-base-uri context article-id)
                (get-url-params db context)))

(reg-event-fx ::navigate [trim-v]
              (fn [{:keys [db]} [context & {:keys [article-id redirect?]}]]
                {:nav [(get-nav-url db context article-id)
                       :redirect redirect?]}))

(reg-event-fx :article-list/load-url-params [trim-v]
              (fn [{:keys [db]} [context]]
                (let [current-filters (get-state db context [:filters])
                      current-text-search (get-state db context [:text-search])
                      {:keys [filters display offset text-search show-article
                              sort-by sort-dir]} (get-params-from-url)]
                  (if show-article
                    ;; show-article url param here is no longer used.
                    ;; This will redirect to valid url for the article.
                    {:nav [(str (:article-base-uri context) "/" show-article)]}
                    (cond-> {:db (-> (set-state db context [:active-article] show-article)
                                     (set-state context [:filters] filters)
                                     (set-state context [:text-search] text-search)
                                     (set-state context [:display-offset] (or offset 0))
                                     (set-state context [:display] display)
                                     (set-state context [:sort-by] sort-by)
                                     (set-state context [:sort-dir] sort-dir))}
                      (or (not= filters current-filters)
                          (not= (or text-search "")
                                (or current-text-search "")))
                      (merge {::reload-list [context :transition]}))))))

(defn sync-url-params
  "Navigate to full browser URL that corresponds to current state"
  [context & {:keys [redirect?] :or {redirect? true}}]
  (dispatch [::navigate context :redirect? redirect?]))

(reg-sub ::ready-state
         (fn [[_ context]] (subscribe [::get (no-cache context)]))
         #(:ready %))

(reg-event-db ::set-recent-nav-action [trim-v]
              (fn [db [context action]]
                (set-state db context [:recent-nav-action] action)))

(reg-sub ::ajax-query-args
         (fn [[_ context]]
           [(subscribe [::filters context])
            (subscribe [::display-offset context])
            (subscribe [::get context [:text-search]])
            (subscribe [::sort-by context])
            (subscribe [::sort-dir context])])
         (fn [[filters display-offset text-search sort-by sort-dir]]
           (let [display-count (get-display-count)]
             {:filters filters
              :text-search text-search
              :sort-by sort-by
              :sort-dir sort-dir
              :n-offset display-offset
              :n-count display-count})))

(reg-sub ::export-filter-args
         (fn [[_ context]]
           [(subscribe [::filters context])
            (subscribe [::get context [:text-search]])])
         (fn [[filters text-search]]
           {:filters filters, :text-search text-search}))

(reg-sub ::articles-query
         (fn [[_ context]]
           [(subscribe [:active-project-id])
            (subscribe [::ajax-query-args context])])
         (fn [[project-id args]]
           [:project/article-list project-id args]))

(reg-sub ::count-query
         (fn [[_ context]]
           [(subscribe [:active-project-id])
            (subscribe [::ajax-query-args context])])
         (fn [[project-id args]]
           (let [args (dissoc args :n-count :n-offset)]
             [:project/article-list-count project-id args])))

(defn sub-articles [context]
  (subscribe @(subscribe [::articles-query context])))

(defn sub-article-count [context]
  (subscribe @(subscribe [::count-query context])))

(defn have-data? [context]
  (let [count-item @(subscribe [::count-query context])
        data-item @(subscribe [::articles-query context])]
    (and @(subscribe [:have? count-item])
         @(subscribe [:have? data-item]))))

(defn data-loading? [context]
  (let [count-item @(subscribe [::count-query context])
        data-item @(subscribe [::articles-query context])]
    (or (loading/item-loading? count-item)
        (loading/item-loading? data-item))))

;; Test if current state is ready to be fully displayed
(reg-sub-raw ::state-ready?
             (fn [_ [_ context]]
               (reaction
                (let [count-item @(subscribe [::count-query context])
                      data-item @(subscribe [::articles-query context])]
                  (and @(subscribe [:have? count-item])
                       @(subscribe [:have? data-item])
                       (not (data-loading? context)))))))

(defn reload-list-count [context]
  (let [item @(subscribe [::count-query context])]
    (dispatch [:reload item])))
(reg-fx ::reload-list-count reload-list-count)

(defn reload-list-data [context]
  (let [item @(subscribe [::articles-query context])]
    (dispatch [:reload item])))
(reg-fx ::reload-list-data reload-list-data)

(defn reload-list [context & [nav-action]]
  (when nav-action
    (dispatch [::set-recent-nav-action context nav-action]))
  (let [count-item @(subscribe [::count-query context])
        data-item @(subscribe [::articles-query context])]
    (dispatch [:data/after-load count-item :reload-list [:reload data-item]])
    (dispatch [:reload count-item])))
(reg-fx ::reload-list #(apply reload-list %))

(defn require-list [context]
  (let [count-item @(subscribe [::count-query context])
        articles-item @(subscribe [::articles-query context])]
    (dispatch [:require count-item])
    (dispatch [:require articles-item])))

(reg-event-db ::set-ready-state [trim-v]
              (fn [db [context new-state reset-pager?]]
                (cond-> (set-state db context [:ready] new-state)
                  reset-pager? (set-state context [:display-offset] 0))))

(defn cache=
  "Tests if subscription value is equal for context and its cache."
  [sub context & args]
  (= @(subscribe (vec (concat [sub context] args)))
     @(subscribe (vec (concat [sub (cached context)] args)))))

(defn ready=
  "Tests if path-keys value is equal for ::get and ::ready-state."
  [context path-keys & [nil-value]]
  (let [get-value #(as-> (get-in % path-keys) v
                     (if (nil? v) nil-value v))]
    (= (get-value @(subscribe [::get context]))
       (get-value @(subscribe [::ready-state context])))))

(defn update-ready-state [context]
  (let [{:keys [base-uri]} context
        project-id @(subscribe [:active-project-id])
        state @(subscribe [::get context])
        ready-state @(subscribe [::ready-state context])
        cleaned (-> state (dissoc :ready :inputs :recent-nav-action))
        changed? (not= ready-state cleaned)
        ready? @(subscribe [::state-ready? context])
        active-article @(subscribe [::get context [:active-article]])]
    (when (and ready? changed? (or (= @active-route base-uri)
                                   (str/starts-with? @active-route (str base-uri "/"))
                                   (str/starts-with? @active-route (str base-uri "?"))))
      (let [redirect? (and (ready= context [:active-article])
                           (ready= context [:display-offset] 0)
                           (ready= context [:filters])
                           (cache= ::sort-by context)
                           (cache= ::sort-dir context))
            reload-article? (and active-article
                                 (not (cache= ::get context [:active-article]))
                                 (not-empty ready-state))
            reset-pager? false]
        (dispatch-sync [::set-ready-state context cleaned reset-pager?])
        (sync-url-params context :redirect? redirect?)
        (when reload-article?
          (dispatch [:reload [:article project-id active-article]]))))
    nil))
