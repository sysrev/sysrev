(ns sysrev.views.article-list.base
  (:require [clojure.string :as str]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx reg-fx trim-v]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer
             [active-panel active-project-id]]
            [sysrev.state.ui :as ui-state]
            [sysrev.shared.article-list :refer
             [is-resolved? resolved-answer is-conflict? is-single? is-consistent?]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? map-values]]))

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
  (if (util/mobile?) 10 20))

(reg-sub
 ::panel
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

(reg-sub-raw
 ::get
 (fn [db [_ context path]]
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

(reg-event-db
 ::set
 [trim-v]
 (fn [db [context path value]]
   (set-state db context path value)))

(defn active-sort-by [state context]
  (if (nil? (:sort-by state))
    (if (-> context :defaults :sort-by)
      (-> context :defaults :sort-by)
      (-> default-options :sort-by))
    (:sort-by state)))

(reg-sub
 ::sort-by
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[state] [_ context]]
   (active-sort-by state context)))

(defn active-sort-dir [state context]
  (if (nil? (:sort-dir state))
    (if (-> context :defaults :sort-dir)
      (-> context :defaults :sort-dir)
      (-> default-options :sort-dir))
    (:sort-dir state)))

(reg-sub
 ::sort-dir
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[state] [_ context]]
   (active-sort-dir state context)))

(reg-sub
 ::display-offset
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[state] [_ context]]
   (or (:display-offset state) 0)))

(reg-sub
 ::active-article
 (fn [[_ context]] [(subscribe [::get context])])
 (fn [[state]] (:active-article state)))

(reg-event-fx
 ::set-active-article
 [trim-v]
 (fn [{:keys [db]} [context article-id]]
   (cond-> {:db (set-state db context [:active-article] article-id)}
     false #_ article-id
     (merge {:dispatch
             [::set-display-option context :expand-filters false]}))))

(defn- get-base-uri [context & [article-id]]
  (let [{:keys [base-uri article-base-uri]} context]
    (assert (string? base-uri))
    (assert (string? article-base-uri))
    (if false #_ article-id
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
(reg-sub
 ::display-options
 (fn [[_ context _ _]]
   [(subscribe [::get context])])
 (fn [[state] [_ context key defaults-only?]]
   (get-display-options-impl state context key defaults-only?)))

(reg-event-db
 ::set-display-option
 [trim-v]
 (fn [db [context key value]]
   (let [state (get-state db context)]
     (if (nil? value)
       (set-state
        db context nil
        (util/dissoc-in state [:display key]))
       (set-state
        db context [:display key]
        value)))))

(defn- filter-to-json [entry]
  (let [[[k v]] (vec entry)
        convert (fn [m field f]
                  (if (contains? m field)
                    (update m field #(some-> % f))
                    m))]
    {k
     (cond->
         (-> v
             (convert :label-id str))
       (= k :consensus)
       (convert :status name))}))

(defn- filter-from-json [entry]
  (let [[[k v]] (vec entry)
        convert (fn [m field f]
                  (if (contains? m field)
                    (update m field #(some-> % f))
                    m))]
    {k
     (cond->
         (-> v
             (convert :label-id sutil/to-uuid)
             (convert :content keyword)
             (convert :confirmed #(case %
                                    "true" true
                                    "false" false
                                    "any" nil
                                    "null" nil
                                    %)))
         (= k :consensus)
         (convert :status keyword))}))

(defn- active-filters-impl [state context & [key]]
  (as-> (cond (-> state :filters not-empty)
              (-> state :filters)

              (-> context :defaults :filters not-empty)
              (-> context :defaults :filters)

              :else [])
      filters
    (if (nil? key)
      (vec filters)
      (->> filters (filterv #(in? (keys %) key))))))
;;
(defn get-active-filters [db context & [key]]
  (active-filters-impl (get-state db context) context key))
;;
(reg-sub
 ::filters
 (fn [[_ context & _]]
   [(subscribe [::get context])])
 (fn [[state] [_ context & [key]]]
   (active-filters-impl state context key)))

(reg-event-db
 ::reset-filters
 [trim-v]
 (fn [db [context]]
   (-> (set-state db context [:filters] nil)
       (set-state context [:display-offset] nil)
       (set-state context [:display] nil))))

(defn- get-url-params-impl [db context]
  (let [{:keys [display-offset active-article
                text-search]
         :as state} (get-state db context)
        sort-by (active-sort-by state context)
        sort-dir (active-sort-dir state context)
        filters (-> (get-active-filters db context)
                    (#(mapv filter-to-json %)))
        display (get-display-options db context)
        display-defaults (get-display-options db context nil true)
        display-changes (->> (keys display)
                             (filter
                              #(not= (boolean (get display %))
                                     (boolean (get display-defaults %))))
                             (select-keys display))]
    (cond-> []
      display-offset
      (conj [:offset display-offset])

      active-article
      (conj [:show-article active-article])

      sort-by
      (conj [:sort-by (name sort-by)])

      sort-by
      (conj [:sort-dir (name sort-dir)])

      (not-empty text-search)
      (conj [:text-search text-search])

      (not-empty display-changes)
      (conj [:display (util/write-json display-changes)])

      (not-empty filters)
      (conj [:filters (util/write-json filters)]))))
;;
(defn- get-url-params [db context]
  (get-url-params-impl db context))

(defn get-params-from-url []
  (let [{:keys [filters text-search display offset show-article
                sort-by sort-dir]}
        (nav/get-url-params)]
    (cond-> {}
      (string? offset)
      (assoc :offset (sutil/parse-integer offset))

      (string? text-search)
      (assoc :text-search text-search)

      (string? sort-by)
      (assoc :sort-by (keyword sort-by))

      (string? sort-dir)
      (assoc :sort-dir (keyword sort-dir))

      (string? show-article)
      (assoc :show-article (sutil/parse-integer show-article))

      (string? filters)
      (assoc :filters (->> (util/read-json filters)
                           (mapv filter-from-json)))

      (string? display)
      (assoc :display (util/read-json display)))))

(defn- get-nav-url [db context & [article-id]]
  (let [url-params (get-url-params db context)
        base-uri (get-base-uri context article-id)]
    (nav/make-url base-uri url-params)))

(reg-event-fx
 ::navigate
 [trim-v]
 (fn [{:keys [db]} [context & {:keys [article-id redirect?]}]]
   (let [url (get-nav-url db context article-id)]
     (if redirect?
       {:nav-redirect url}
       {:nav url}))))

(reg-event-fx
 :article-list/load-url-params
 [trim-v]
 (fn [{:keys [db]} [context]]
   (let [current-filters (get-state db context [:filters])
         current-text-search (get-state db context [:text-search])
         {:keys [filters display offset text-search show-article
                 sort-by sort-dir]}
         (get-params-from-url)]
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
       (merge {::reload-list [context :transition]})))))

(defn sync-url-params
  "Navigate to full browser URL that corresponds to current state"
  [context & {:keys [redirect?] :or {redirect? true}}]
  (dispatch [::navigate context :redirect? redirect?]))

(reg-sub
 ::ready-state
 (fn [[_ context]]
   [(subscribe [::get (no-cache context)])])
 (fn [[state] [_ context]] (:ready state)))

(reg-event-db
 ::set-recent-nav-action
 [trim-v]
 (fn [db [context action]]
   (set-state db context [:recent-nav-action] action)))

(reg-sub
 ::ajax-query-args
 (fn [[_ context]]
   [(subscribe [::filters context])
    (subscribe [::display-offset context])
    (subscribe [::get context [:text-search]])
    (subscribe [::sort-by context])
    (subscribe [::sort-dir context])])
 (fn [[filters display-offset text-search sort-by sort-dir]]
   (let [display-count (get-display-count)]
     (merge {:filters filters
             :text-search text-search
             :sort-by sort-by
             :sort-dir sort-dir
             :n-offset display-offset
             :n-count display-count}))))

(reg-sub
 ::articles-query
 (fn [[_ context]]
   [(subscribe [:active-project-id])
    (subscribe [::ajax-query-args context])])
 (fn [[project-id args]]
   [:project/article-list project-id args]))

(reg-sub
 ::count-query
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
(reg-sub-raw
 ::state-ready?
 (fn [_ [_ context]]
   (reaction
    (let [count-item @(subscribe [::count-query context])
          data-item @(subscribe [::articles-query context])]
      (and @(subscribe [:have? count-item])
           @(subscribe [:have? data-item])
           (not (data-loading? context)))))))

(defn reload-list-count [context]
  (let [item @(subscribe [::count-query context])]
    #_ (dispatch [:require item])
    (dispatch [:reload item])))
(reg-fx ::reload-list-count reload-list-count)

(defn reload-list-data [context]
  (let [item @(subscribe [::articles-query context])]
    #_ (dispatch [:require item])
    (dispatch [:reload item])))
(reg-fx ::reload-list-data reload-list-data)

(defn reload-list [context & [nav-action]]
  (when nav-action
    (dispatch [::set-recent-nav-action context nav-action]))
  (reload-list-count context)
  (reload-list-data context))
(reg-fx ::reload-list #(apply reload-list %))

(defn require-list [context]
  (let [count-item @(subscribe [::count-query context])
        articles-item @(subscribe [::articles-query context])]
    (dispatch [:require count-item])
    (dispatch [:require articles-item])))

(reg-event-db
 ::set-ready-state
 [trim-v]
 (fn [db [context new-state reset-pager?]]
   (cond-> (set-state db context [:ready] new-state)
     reset-pager? (set-state context [:display-offset] 0))))

(defn update-ready-state [context]
  (let [ready? @(subscribe [::state-ready? context])
        ready-state @(subscribe [::ready-state context])
        state @(subscribe [::get context])
        cleaned (-> state (dissoc :ready :inputs :recent-nav-action))
        changed? (not= ready-state cleaned)

        sort-by-now @(subscribe [::sort-by context])
        sort-by-cached @(subscribe [::sort-by (cached context)])
        sort-dir-now @(subscribe [::sort-dir context])
        sort-dir-cached @(subscribe [::sort-dir (cached context)])
        count-now @(subscribe [::count-query context])
        count-cached @(subscribe [::count-query (cached context)])
        article-now @(subscribe [::get context [:active-article]])
        article-cached @(subscribe [::get (cached context) [:active-article]])
        project-id @(subscribe [:active-project-id])]
    (when (and ready? changed?)
      #_ (println (str "current = " (pr-str ready-state)))
      #_ (println (str "next = " (pr-str cleaned)))
      (let [redirect?
            (if (or (not= (:active-article ready-state)
                          (:active-article state))
                    (not= (or (:display-offset ready-state) 0)
                          (or (:display-offset state) 0))
                    (not= (:filters ready-state)
                          (:filters state))
                    (not= sort-by-now sort-by-cached)
                    (not= sort-dir-now sort-dir-cached))
              false true)
            reset-pager? false
            #_ (and (not-empty ready-state)
                    (not= count-now count-cached))
            reload-article?
            (and article-now
                 (not= article-now article-cached)
                 (not-empty ready-state))]
        (dispatch-sync [::set-ready-state context cleaned reset-pager?])
        (sync-url-params context :redirect? redirect?)
        (when reload-article?
          (dispatch [:reload [:article project-id article-now]]))))
    nil))