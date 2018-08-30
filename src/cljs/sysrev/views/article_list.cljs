(ns sysrev.views.article-list
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
              reg-event-db reg-event-fx reg-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.base :refer [use-new-article-list?]]
            [sysrev.loading :as loading]
            [sysrev.nav :as nav :refer [nav]]
            [sysrev.state.nav :refer
             [project-uri active-panel active-project-id]]
            [sysrev.state.ui :as ui-state]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.state.project.core :refer [get-project-uri]]
            [sysrev.state.labels :refer [sort-project-labels]]
            [sysrev.shared.keywords :refer [canonical-keyword]]
            [sysrev.shared.article-list :refer
             [is-resolved? resolved-answer is-conflict? is-single? is-consistent?]]
            [sysrev.views.article :refer [article-info-view]]
            [sysrev.views.review :refer [label-editor-view]]
            [sysrev.views.components :as ui]
            [sysrev.views.list-pager :refer [ListPager]]
            [sysrev.util :as util :refer [nbsp]]
            [sysrev.shared.util :as sutil :refer [in? map-values]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def view :article-list)

(def default-options
  {:display {:expand-filters true
             :show-inclusion true
             :show-labels false
             :show-notes false}
   :sort-by :article-id
   :sort-dir :desc
   :private-view? false})

(defn- get-display-count []
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
(defn- cached [context] (assoc context :read-cache? true))
(defn- no-cache [context] (dissoc context :read-cache?))

(defn- get-state [db context & [path]]
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

(defn- get-ready-state [db context]
  (get-state db (no-cache context) [:ready]))

(defn- set-state [db context path value]
  (let [panel (get-panel db context)
        path (or path [])]
    (ui-state/set-view-field db view path value panel)))

(reg-event-db
 ::set
 [trim-v]
 (fn [db [context path value]]
   (set-state db context path value)))



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
(defn- get-active-filters [db context & [key]]
  (active-filters-impl (get-state db context) context key))
;;
(reg-sub
 ::filters
 (fn [[_ context & _]]
   [(subscribe [::get context])])
 (fn [[state] [_ context & [key]]]
   (active-filters-impl state context key)))

(reg-event-fx
 ::reset-filters
 [trim-v]
 (fn [{:keys [db]} [context]]
   {:dispatch-n
    (list [::set context [:filters] nil]
          [::set context [:display-offset] nil])}))

(reg-sub
 ::sort-by
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[state] [_ context]]
   (if (nil? (:sort-by state))
     (if (-> context :defaults :sort-by)
       (-> context :defaults :sort-by)
       (-> default-options :sort-by))
     (:sort-by state))))

(reg-sub
 ::sort-dir
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[state] [_ context]]
   (if (nil? (:sort-dir state))
     (if (-> context :defaults :sort-dir)
       (-> context :defaults :sort-dir)
       (-> default-options :sort-dir))
     (:sort-dir state))))

(reg-sub
 ::private-view?
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[state] [_ context]]
   (cond (-> state :private-view? ((comp not nil?)))
         (-> state :private-view?)

         (-> context :private-view? ((comp not nil?)))
         (-> context :private-view?)

         :else
         (-> default-options :private-view?))))

(reg-sub
 ::display-offset
 (fn [[_ context]]
   [(subscribe [::get context])])
 (fn [[state] [_ context]]
   (or (:display-offset state) 0)))

(reg-event-fx
 ::set-display-offset
 [trim-v]
 (fn [{:keys [db]} [context offset]]
   (let [cur-offset (get-state db context [:display-offset])]
     (cond->
         {:db (set-state db context [:display-offset] offset)}
       (not= (or offset 0) (or cur-offset 0))
       (merge {::reload-list-data true})))))

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
(defn- get-display-options [db context & [key defaults-only?]]
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

(defn filter-to-json [entry]
  (let [[[fkey value]] (vec entry)]
    (case fkey
      ;; :label-id {key (str value)}
      {fkey value})))

(defn filter-from-json [entry]
  (let [[[fkey value]] (vec entry)]
    (case fkey
      :has-user
      {fkey (-> value
                (update :content keyword)
                (update :confirmed #(case %
                                      "true" true
                                      "false" false
                                      "any" nil
                                      "null" nil
                                      %)))}
      ;; :label-id {key (sutil/to-uuid value)}
      {key value})))

(defn- get-url-params-impl [db context]
  (let [{:keys [display-offset active-article
                text-search]} (get-state db context)
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

      (not-empty text-search)
      (conj [:text-search text-search])

      (not-empty display-changes)
      (conj [:display (util/write-json display-changes)])

      (not-empty filters)
      (conj [:filters (util/write-json filters)]))))
;;
(defn- get-url-params [db context]
  (get-url-params-impl db context))

(defn- get-params-from-url []
  (let [{:keys [filters text-search display offset show-article]}
        (nav/get-url-params)]
    (cond-> {}
      (string? offset)
      (assoc :offset (sutil/parse-integer offset))

      (string? text-search)
      (assoc :text-search text-search)

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
   (let [active-panel (active-panel db)
         {:keys [filters display offset text-search show-article]}
         (get-params-from-url)
         ;; active-filters (get-active-filters db context)
         new-db
         (cond->
             (-> db
                 (set-state context [:active-article] show-article)
                 (set-state context [:filters] filters)
                 (set-state context [:text-search] text-search)
                 (set-state context [:display-offset] (or offset 0)))
           (not-empty display)
           (set-state context [:display] display))]
     (cond-> {:db new-db}
       false #_ show-article
       (merge {:dispatch [::set-display-option context
                          :expand-filters false]})))))

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

(def group-statuses
  [:single :determined :conflict :consistent :resolved])

(reg-sub
 ::ajax-query-args
 (fn [[_ context]]
   [(subscribe [::filters context])
    (subscribe [::display-offset context])
    (subscribe [::get context [:text-search]])])
 (fn [[filters display-offset text-search]]
   (let [display-count (get-display-count)]
     (merge {:filters filters
             :text-search text-search
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

(defn- sub-articles [context]
  (subscribe @(subscribe [::articles-query context])))
(defn- sub-article-count [context]
  (subscribe @(subscribe [::count-query context])))

(defn- data-loading? [context]
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

(defn- reload-list-count [context]
  (let [item @(subscribe [::count-query context])]
    (dispatch [:require item])
    (dispatch [:reload item])))
(reg-fx ::reload-list-count reload-list-count)

(defn- reload-list-data [context]
  (let [item @(subscribe [::articles-query context])]
    (dispatch [:require item])
    (dispatch [:reload item])))
(reg-fx ::reload-list-data reload-list-data)

(defn reload-list [context & [nav-action]]
  (when nav-action
    (dispatch [::set-recent-nav-action context nav-action]))
  (reload-list-count context)
  (reload-list-data context))
(reg-fx ::reload-list #(reload-list %))

(reg-event-db
 ::set-ready-state
 [trim-v]
 (fn [db [context new-state reset-pager?]]
   (cond-> (set-state db context [:ready] new-state)
     reset-pager? (set-state context [:display-offset] 0))))

(defn- update-ready-state [context]
  (let [ready? @(subscribe [::state-ready? context])
        ready-state @(subscribe [::ready-state context])
        state @(subscribe [::get context])
        cleaned (-> state (dissoc :ready :inputs :recent-nav-action))
        changed? (not= ready-state cleaned)

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
                          (or (:display-offset state) 0)))
              false true)
            reset-pager?
            (and (not-empty ready-state)
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

(reg-sub-raw
 ::prev-next-article-ids
 (fn [_ [_ context]]
   (reaction
    (let [articles @(sub-articles context)
          active-id @(subscribe [::get context [:active-article]])
          visible-ids (map :article-id articles)]
      (when (in? visible-ids active-id)
        {:prev-id
         (->> visible-ids
              (take-while #(not= % active-id))
              last)
         :next-id
         (->> visible-ids
              (drop-while #(not= % active-id))
              (drop 1)
              first)})))))

;; TODO: do these work?

(reg-sub
 ::resolving-allowed?
 (fn [[_ context article-id]]
   [(subscribe [::get context [:active-article]])
    (subscribe [:article/review-status article-id])
    (subscribe [:member/resolver?])
    (subscribe [::private-view? context])])
 (fn [[active-id review-status resolver? private-view?]
      [_ context article-id]]
   (when (= article-id active-id)
     (boolean
      (and (not private-view?)
           (= :conflict review-status)
           resolver?)))))

(reg-sub
 ::editing-allowed?
 (fn [[_ context article-id]]
   [(subscribe [::get context [:active-article]])
    (subscribe [::resolving-allowed? context article-id])
    (subscribe [:article/user-status article-id])])
 (fn [[active-id can-resolve? user-status]
      [_ context article-id]]
   (when (= article-id active-id)
     (boolean
      (or can-resolve?
          (in? [:confirmed :unconfirmed] user-status))))))

(reg-sub
 ::inputs
 (fn [[_ context path]]
   [(subscribe [::get context (concat [:inputs] path)])])
 (fn [[input-val]] input-val))

(defn create-filter [filter-type]
  {filter-type
   (merge
    (case filter-type
      :has-content    {:content nil ;; [:label :annotation :note]
                       :users nil
                       :search nil ;; for notes
                       }
      :has-label      {:label-id nil
                       :users nil
                       :values nil
                       :confirmed true}
      :has-annotation {:semantic-class nil
                       :has-value nil
                       :users nil}
      :has-user       {:user nil
                       :content nil
                       :confirmed nil}
      :inclusion      {:label-id nil
                       :values nil}
      :consensus      {:status nil})
    {:editing? true})})

(defn filter-presets []
  (let [self-id @(subscribe [:self/user-id])
        overall-id @(subscribe [:project/overall-label-id])]
    {:self
     [{:has-user {:user self-id
                  :content nil
                  :confirmed nil}}]

     :content
     [{:has-content {:content nil
                     :confirmed true}}]

     :inclusion
     [{:inclusion {:label-id overall-id
                   :values []}}
      {:consensus {:status nil}}]}))

(defn- reset-filters-input [db context]
  (set-state db context [:inputs :filters] nil))

(reg-event-db
 ::reset-filters-input
 [trim-v]
 (fn [db [context]]
   (reset-filters-input db context)))

(defn- get-filters-input [db context]
  (let [input (get-state db context [:inputs :filters])
        active (get-active-filters db context)]
    (if (nil? input) active input)))

(reg-sub
 ::filters-input
 (fn [[_ context]]
   [(subscribe [::inputs context [:filters]])
    (subscribe [::filters context])])
 (fn [[input active]]
   (if (nil? input) active input)))

(reg-event-db
 ::update-filter-input
 [trim-v]
 (fn [db [context filter-idx update-fn]]
   (let [ifilter (get-state db context [:inputs :filters filter-idx])
         filter-type (first (keys ifilter))
         value (first (vals ifilter))]
     (set-state db context [:inputs :filters filter-idx]
                {filter-type (update-fn value)}))))

(defn- remove-null-filters [db context]
  (let [ifilters (get-state db context [:inputs :filters])
        filters (get-state db context [:filters])]
    (cond-> db
      (not-empty ifilters)
      (set-state context [:inputs :filters]
                 (->> ifilters (remove nil?) vec))
      (not-empty filters)
      (set-state context [:filters]
                 (->> filters (remove nil?) vec)))))

(defn- delete-filter [db context filter-idx]
  (let [ifilters (get-state db context [:inputs :filters])
        filters (get-state db context [:filters])]
    (cond-> db
      (contains? ifilters filter-idx)
      (set-state context [:inputs :filters filter-idx] nil)
      (contains? filters filter-idx)
      (set-state context [:filters filter-idx] nil)
      true
      (remove-null-filters context))))

(reg-event-db
 ::delete-filter
 [trim-v]
 (fn [db [context filter-idx]]
   (delete-filter db context filter-idx)))

(defn- edit-filter [db context filter-idx]
  (let [filters (get-state db context [:filters])]
    (if (and (vector? filters)
             (contains? filters filter-idx))
      (set-state
       db context [:inputs :filters]
       (vec (map-indexed
             (fn [i entry]
               (let [[[fkey fval]] (vec entry)]
                 (if (= i filter-idx)
                   {fkey (assoc fval :editing? true)}
                   {fkey (dissoc fval :editing?)})))
             filters)))
      db)))

(reg-event-db
 ::edit-filter [trim-v]
 (fn [db [context filter-idx]]
   (edit-filter db context filter-idx)))

;; TODO: is this needed?
(defn- process-filter-input [ifilter]
  (let [[[filter-type value]] (vec ifilter)
        nilify #(if (= % :any) nil %)]
    (case filter-type
      :has-user
      (let [{:keys [user content confirmed]} value]
        {filter-type
         {:user (if (integer? user) user nil)
          :content (nilify content)
          :confirmed (nilify confirmed)}})

      nil)))

(defn- process-all-filters-input [ifilters]
  (->> ifilters
       (mapv #(let [[[filter-type value]] (vec %)
                    {:keys [editing?]} value]
                (if editing?
                  (process-filter-input %)
                  %)))
       (filterv (comp not nil?))))

(reg-event-db
 ::sync-filters-input
 [trim-v]
 (fn [db [context]]
   (->
    (->> (get-state db context [:inputs :filters])
         (process-all-filters-input)
         (set-state db context [:filters]))
    (set-state context [:inputs :filters] nil))))

(reg-event-db
 ::add-filter
 [trim-v]
 (fn [db [context filter-type]]
   (let [filters (get-filters-input db context)
         new-filter (create-filter filter-type)]
     (if (in? filters new-filter)
       db
       (set-state db context (concat [:inputs :filters])
                  (->> [new-filter]
                       (concat filters) vec))))))

(reg-sub
 ::editing-filter?
 (fn [[_ context]]
   [(subscribe [::filters context])
    (subscribe [::filters-input context])])
 (fn [[active input]]
   (boolean (some #(not (in? active %)) input))))

(defn- TextSearchInput [context]
  (let [input (subscribe [::inputs context [:text-search]])
        set-input #(dispatch-sync [::set context [:inputs :text-search] %])
        curval @(subscribe [::get context [:text-search]])
        synced? (or (nil? @input) (= @input curval))]
    [:div.ui.fluid.left.icon.input
     {:class (when-not synced? "loading")}
     [:input
      {:type "text"
       :id "article-search"
       :value (or @input curval)
       :placeholder "Search articles"
       :on-change
       (util/wrap-prevent-default
        (fn [event]
          (let [value (-> event .-target .-value)]
            (set-input value)
            (js/setTimeout
             #(let [later-value (if (empty? @input) nil @input)
                    value (if (empty? value) nil value)]
                (when (= value later-value)
                  (dispatch [::set context [:text-search] value])
                  (when (empty? value)
                    (set-input nil))))
             1000))))}]
     [:i.search.icon]]))

(defn- FilterDropdown [options option-name active on-change multiple? read-value]
  (let [touchscreen? @(subscribe [:touchscreen?])
        to-data-value #(cond (nil? %)          "any"
                             (or (symbol? %)
                                 (keyword? %)) (name %)
                             :else             (str %))]
    [ui/selection-dropdown
     [:div.text (option-name active)]
     (map-indexed
      (fn [i x]
        [:div.item
         {:key [:filter-item i]
          :data-value (to-data-value x)
          :class (when (= x active)
                   "active selected")}
         (option-name x)])
      options)
     {:class
      (cond-> "ui fluid"
        multiple?          (str " multiple")
        (not touchscreen?) (str " search")
        true               (str " selection dropdown"))
      :onChange
      (fn [v t]
        #_ (println (str "v = " (pr-str v)))
        (let [v (cond (= v "any")  nil
                      read-value   (read-value v)
                      :else        v)]
          (when on-change
            (on-change v))))}]))

(defn- SelectUserDropdown [context value on-change multiple?]
  (let [user-ids @(subscribe [:project/member-user-ids nil true])
        self-id @(subscribe [:self/user-id])
        value (if (= value :self)
                (if (and self-id (in? user-ids self-id))
                  self-id
                  nil)
                value)]
    [FilterDropdown
     (concat [nil] user-ids)
     #(if (or (nil? %) (= :any %))
        "Any User"
        @(subscribe [:user/display %]))
     value
     on-change
     multiple?
     #(sutil/parse-integer %)]))

(defn- SelectLabelDropdown [context value on-change]
  (let [label-ids @(subscribe [:project/label-ids])
        {:keys [labels]} @(subscribe [:project/raw])]
    [FilterDropdown
     (concat [nil] label-ids)
     #(if (or (nil? %) (= :any %))
        "Any Label"
        (get-in labels [% :short-label]))
     value
     on-change
     false
     #(if (in? label-ids (uuid %))
        (uuid %) nil)]))

(defn- ContentTypeDropdown [context value on-change]
  [FilterDropdown
   [nil :labels :annotations]
   #(case %
      :labels "Labels"
      :annotations "Annotations"
      "Any")
   value
   on-change
   false
   #(keyword %)])

(defn- ConfirmedStatusSelector [context value on-change]
  [FilterDropdown
   [nil true false]
   #(cond (= % true)  "Yes"
          (= % false) "No"
          :else       "Any")
   value
   on-change
   false
   #(cond (= % "true")  true
          (= % "false") false
          :else         nil)])


;; TODO: break up into multiple functions
(defn- FilterEditElement
  [context filter-idx]
  [:div.edit-filter
   (let [filters @(subscribe [::filters-input context])
         fr (nth filters filter-idx)
         [[filter-type value]] (vec fr)
         update-filter #(dispatch [::update-filter-input context filter-idx %])
         valid? ((comp not nil?) (process-filter-input fr))
         buttons-field
         (fn []
           [:div.field
            [:div.fields
             [:div.eight.wide.field
              [:div.ui.tiny.fluid.labeled.icon.button
               {:class (if valid? nil "disabled")
                :on-click
                (when valid?
                  (util/wrap-user-event
                   #(dispatch [::sync-filters-input context])))}
               [:i.circle.check.icon]
               "Save"]]
             [:div.eight.wide.field
              [:div.ui.tiny.fluid.labeled.icon.button
               {:on-click
                (util/wrap-user-event
                 #(dispatch [::reset-filters-input context]))}
               [:i.times.icon]
               "Cancel"]]]])
         fields
         (case filter-type
           :has-label
           (let [{:keys [label-id users values confirmed]} value]
             [[:div.field
               {:key [:has-label 1]}
               [:div.fields
                [:div.eight.wide.field
                 [:label "Label"]
                 [SelectLabelDropdown context nil nil]]
                [:div.eight.wide.field]]]])

           :has-user
           (let [{:keys [user content confirmed]} value]
             [[:div.field
               {:key [:has-user 1]}
               [:div.fields
                [:div.eight.wide.field
                 [:label "User"]
                 [SelectUserDropdown context user
                  (fn [new-value]
                    (update-filter
                     #(assoc % :user new-value)))
                  false]]
                [:div.eight.wide.field
                 [:label "Content Type"]
                 [ContentTypeDropdown context content
                  (fn [new-value]
                    (update-filter
                     #(assoc % :content new-value)))]]]]
              (when (or (= content :labels)
                        (in? [true false] confirmed))
                [:div.field
                 {:key [:has-user 2]}
                 [:div.fields
                  [:div.eight.wide.field
                   [:label "Confirmed"]
                   [ConfirmedStatusSelector
                    context confirmed
                    (fn [new-value]
                      (update-filter
                       #(assoc % :confirmed new-value)))]]]])])

           [[:div.sixteen.wide.field
             {:key [:unknown-filter 1]}
             [:div.ui.tiny.fluid.button
              (str "editing filter (" (pr-str filter-type) ")")]]])]
     [:div.ui.small.form
      (doall (for [field (remove nil? fields)] field))
      [buttons-field]])])

(defn- FilterDescribeElement
  [context filter-idx]
  [:div.describe-filter
   (if (nil? filter-idx)
     [:div.ui.tiny.fluid.button
      "all articles"]
     (let [{:keys [labels]} @(subscribe [:project/raw])
           filters @(subscribe [::filters-input context])
           fr (nth filters filter-idx)
           [[filter-type value]] (vec fr)
           label-name #(get-in labels [% :short-label])
           description
           (case filter-type
             :has-user
             (let [{:keys [user content confirmed]} value
                   content-str (case content
                                 :labels "labels"
                                 :annotations "annotations"
                                 "content")
                   user-name (when (integer? user)
                               @(subscribe [:user/display user]))
                   user-str (if (integer? user)
                              (str "user " (pr-str user-name))
                              "any user")
                   confirmed-str (case confirmed
                                   true "[confirmed only]"
                                   false "[unconfirmed only]"
                                   nil)]
               (->> ["has" content-str "from" user-str confirmed-str]
                    (remove nil?)
                    (str/join " ")))

             (str "unknown filter (" (pr-str filter-type) ")"))]
       [:div.ui.center.aligned.middle.aligned.grid
        [:div.row
         [:div.middle.aligned.two.wide.column.delete-filter
          [:div.ui.tiny.fluid.icon.button
           {:on-click #(dispatch-sync [::delete-filter context filter-idx])}
           [:i.times.icon]]]
         [:div.middle.aligned.fourteen.wide.column
          [:div.ui.tiny.fluid.button
           {:on-click #(dispatch-sync [::edit-filter context filter-idx])}
           description]]]]))])

(defn- TextSearchDescribeElement [context]
  (let [text-search @(subscribe [::get context [:text-search]])]
    (when (and (string? text-search)
               (not-empty text-search))
      [:div.ui.tiny.fluid.button
       (str "text contains " (pr-str text-search))])))

(defn- ArticleListFiltersRow [context]
  (let [get-val #(deref (subscribe [::get context %]))
        recent-nav-action (get-val [:recent-nav-action])
        {:keys [expand-filters]
         :as display-options} @(subscribe [::display-options context])
        project-id @(subscribe [:active-project-id])
        {:keys [labels]} @(subscribe [:project/raw])
        filters @(subscribe [::filters context])
        loading? (and (loading/any-loading? :only :project/article-list)
                      @(loading/loading-indicator))
        view-button
        (fn [option-key label]
          (let [status (get display-options option-key)]
            [:button.ui.small.labeled.icon.button
             {:on-click
              (util/wrap-user-event
               #(dispatch [::set-display-option
                           context option-key (not status)]))}
             (if status
               [:i.green.circle.icon]
               [:i.grey.circle.icon])
             label]))]
    [:div.ui.segments.article-filters.article-filters-row
     [:div.ui.secondary.middle.aligned.grid.segment.filters-minimal
      [:div.row
       [:div.one.wide.column.medium-weight.filters-header
        "Filters"]
       [:div.nine.wide.column.filters-summary
        [:span
         #_
         (doall
          (for [filter-idx ...]
            [FilterDescribeElement context filter-idx]))]]
       [:div.six.wide.column.right.aligned.control-buttons
        [:button.ui.small.icon.button
         {:class (if (and loading? (= recent-nav-action :refresh))
                   "loading" "")
          :on-click (util/wrap-user-event
                     #(reload-list context :refresh))}
         [:i.repeat.icon]]
        [:button.ui.small.icon.button
         {:on-click (util/wrap-user-event
                     #(dispatch [::reset-filters context]))}
         [:i.erase.icon]]]]]
     [:div.ui.secondary.segment.no-padding
      [:button.ui.tiny.fluid.icon.button
       {:style (cond-> {:border-top-left-radius "0"
                        :border-top-right-radius "0"}
                 expand-filters
                 (merge {:border-bottom-left-radius "0"
                         :border-bottom-right-radius "0"}))
        :on-click (util/wrap-user-event
                   #(dispatch [::set-display-option
                               context :expand-filters (not expand-filters)]))}
       (if expand-filters
         [:i.chevron.up.icon]
         [:i.chevron.down.icon])]]
     (when expand-filters
       [:div.ui.secondary.segment.filters-content
        [:div.ui.small.form
         [:div.field
          [:div.fields
           [:div.four.wide.field
            [:label "Text search"]
            [TextSearchInput context]]
           [:div.six.wide.field
            [:label "View Options"]
            [view-button :show-inclusion "Inclusion"]
            [view-button :show-labels "Labels"]
            [view-button :show-notes "Notes"]]
           [:div.six.wide.field]]]]])]))

(defn- NewFilterElement [context]
  (when (not @(subscribe [::editing-filter? context]))
    (let [items [[:has-content "Content" "pencil alternate"]
                 [:has-label "Label" "tags"]
                 [:has-annotation "Annotation" "quote left"]
                 [:has-user "User" "user"]
                 [:inclusion "Inclusion" "check circle"]
                 [:consensus "Consensus" "users"]]
          touchscreen? @(subscribe [:touchscreen?])]
      [:div.ui.small.form
       [ui/selection-dropdown
        [:div.text "Add filter..."]
        (map-indexed
         (fn [i [ftype label icon]]
           [:div.item
            {:key [:new-filter-item i]
             :data-value (name ftype)}
            [:i {:class (str icon " icon")}]
            (str label)])
         items)
        {:class
         (if true #_ touchscreen?
             "ui fluid selection dropdown"
             "ui fluid search selection dropdown")
         :onChange
         (fn [v t]
           (when (not-empty v)
             (dispatch-sync [::add-filter context (keyword v)])))}]])))

(defn- FilterPresetsForm [context]
  (let [filters @(subscribe [::filters-input context])
        presets (filter-presets)
        make-button
        (fn [key icon-class]
          (let [preset (get presets key)]
            [:button.ui.tiny.fluid.icon.button
             {:class (if (= filters preset)
                       "primary")
              :on-click
              (util/wrap-user-event
               #(dispatch-sync
                 [::set context [:filters]
                  (process-all-filters-input (get presets key))]))}
             [:i {:class (str icon-class " icon")}]]))]
    [:div.ui.secondary.segment.filter-presets
     [:form.ui.small.form
      {:on-submit (util/wrap-prevent-default #(do nil))}
      [:div.sixteen.wide.field
       [:label "Presets"]
       [:div.ui.three.column.grid
        [:div.column
         [make-button :self "user"]]
        [:div.column
         [make-button :content "tags"]]
        [:div.column
         [make-button :inclusion "circle plus"]]]]]]))

(defn- DisplayOptionsForm [context]
  (let [options @(subscribe [::display-options context])
        make-button
        (fn [key icon-class]
          (let [enabled? (true? (get options key))]
            [:button.ui.tiny.fluid.icon.labeled.button
             {:on-click
              (util/wrap-user-event
               #(dispatch-sync [::set-display-option context key (not enabled?)]))}
             (if enabled?
               [:i.green.circle.icon]
               [:i.grey.circle.icon])
             [:span [:i {:class (str icon-class " icon")}]]]))]
    [:div.ui.secondary.segment.display-options
     [:div.ui.small.form
      [:div.sixteen.wide.field
       [:label "Display Options"]
       [:div.ui.three.column.grid
        [:div.column
         [make-button :show-inclusion "circle plus"]]
        [:div.column
         [make-button :show-labels "tags"]]
        [:div.column
         [make-button :show-notes "pencil alternate"]]]]]]))

(defn- ArticleListFiltersColumn [context]
  (let [{:keys [expand-filters] :as display-options}
        @(subscribe [::display-options (cached context)])
        project-id @(subscribe [:active-project-id])
        active-filters @(subscribe [::filters context])
        input-filters @(subscribe [::filters-input context])
        active-article @(subscribe [::get (cached context) [:active-article]])
        loading? (and (loading/any-loading? :only :project/article-list)
                      @(loading/loading-indicator))
        view-button
        (fn [option-key label]
          (let [status (get display-options option-key)]
            [:button.ui.small.labeled.icon.button
             {:on-click
              (util/wrap-user-event
               #(dispatch [::set-display-option
                           context option-key (not status)]))}
             (if status
               [:i.green.circle.icon]
               [:i.grey.circle.icon])
             label]))]
    (if (or active-article (not expand-filters))
      [:div.ui.segments.article-filters.article-filters-column.expand-header
       {:on-click
        (util/wrap-user-event
         #(do (dispatch-sync [::set-display-option
                              context :expand-filters true])
              (dispatch-sync [::set-active-article context nil])))}
       [:div.ui.center.aligned.secondary.header.segment
        "Filters"]
       [:div.ui.one.column.center.aligned.secondary.grid.segment.expand-filters
        [:div.column
         [:i.fitted.angle.double.right.icon]]]]
      [:div.ui.segments.article-filters.article-filters-column
       [:a.ui.center.aligned.secondary.header.grid.segment.collapse-header
        {:on-click
         (util/wrap-user-event
          #(dispatch-sync [::set-display-option
                           context :expand-filters false]))}
        [:div.two.wide.column.left.aligned
         [:i.fitted.angle.double.left.icon]]
        [:div.twelve.wide.column
         "Filters"]
        [:div.two.wide.column.right.aligned
         [:i.fitted.angle.double.left.icon]]]
       [FilterPresetsForm context]
       [DisplayOptionsForm context]
       [:div.ui.secondary.segment.filters-content
        [:div.inner
         (when (not-empty input-filters)
           [:div.ui.tiny.fluid.icon.labeled.button
            {:on-click #(do (dispatch-sync [::reset-filters-input context])
                            (dispatch-sync [::reset-filters context]))}
            [:i.times.icon]
            "Reset Filters"])
         [TextSearchDescribeElement context]
         (if (empty? input-filters)
           [FilterDescribeElement context nil]
           (doall
            (map-indexed
             (fn [filter-idx fr] ^{:key [:filter-element filter-idx]}
               (if (in? active-filters fr)
                 ^{:key [:filter-text filter-idx]}
                 [FilterDescribeElement context filter-idx]
                 ^{:key [:filter-editor filter-idx]}
                 [FilterEditElement context filter-idx]))
             input-filters)))
         [NewFilterElement context]]]])))

(defn- ArticleListNavHeader [context]
  (let [count-now @(sub-article-count context)
        count-cached @(sub-article-count (cached context))
        recent-action @(subscribe [::get context [:recent-nav-action]])]
    [:div.ui.segment.article-nav
     [ListPager
      {:panel (:panel context)
       :instance-key [:article-list]
       :offset @(subscribe [::display-offset context])
       :total-count count-now
       :items-per-page (get-display-count)
       :item-name-string "articles"
       :set-offset #(dispatch [::set-display-offset context %])
       :on-nav-action
       (fn [action offset]
         (dispatch-sync [::set-recent-nav-action context action])
         (dispatch-sync [::set-active-article context nil]))
       :recent-nav-action recent-action
       :loading? (or ((comp not nil?) recent-action)
                     (loading/any-loading? :only :project/article-list)
                     (loading/any-loading? :only :project/article-list-count))
       :message-overrides
       {:offset @(subscribe [::display-offset (cached context)])
        :total-count count-cached}}]]))

(defn- AnswerCellIcon [value]
  (case value
    true  [:i.green.circle.plus.icon]
    false [:i.orange.circle.minus.icon]
    [:i.grey.question.mark.icon]))

(defn- AnswerCell [article-id labels answer-class]
  [:div.ui.divided.list
   (doall
    (map (fn [entry]
           (let [{:keys [user-id inclusion]} entry]
             (when (or (not= answer-class "resolved")
                       (:resolve entry))
               [:div.item {:key [:answer article-id user-id]}
                (AnswerCellIcon inclusion)
                [:div.content>div.header
                 @(subscribe [:user/display user-id])]])))
         labels))])

(defn- ArticleContent [context article-id]
  (let [editing-allowed? @(subscribe [::editing-allowed? context article-id])
        resolving-allowed? @(subscribe [::resolving-allowed? context article-id])
        editing? @(subscribe [:article-list/editing? context article-id])
        private-view? @(subscribe [::private-view? context])]
    [:div
     [article-info-view article-id
      :show-labels? true
      :private-view? private-view?
      :context :article-list]
     (cond editing?
           [label-editor-view article-id]

           editing-allowed?
           [:div.ui.segment
            [:div.ui.fluid.button
             {:on-click
              (util/wrap-user-event
               #(dispatch [:review/enable-change-labels
                           article-id (:panel context)]))}
             (if resolving-allowed? "Resolve Labels" "Change Labels")]])]))

(defn- ArticleListEntry [context article full-size?]
  (let [active-article @(subscribe [::get context [:active-article]])
        overall-id @(subscribe [:project/overall-label-id])
        {:keys [article-id primary-title labels updated-time]} article
        active? (and active-article (= article-id active-article))
        overall-labels (->> labels (filter #(= (:label-id %) overall-id)))
        answer-class
        (cond
          (is-resolved? overall-labels) "resolved"
          (is-consistent? overall-labels) "consistent"
          (is-single? overall-labels) "single"
          :else "conflict")]
    (if full-size?
      ;; non-mobile view
      [:div.ui.row
       [:div.ui.thirteen.wide.column.article-title
        [:div.ui.middle.aligned.grid
         [:div.row
          [:div.fourteen.wide.column
           {:style {:padding-right "0"}}
           [:div.ui.middle.aligned.grid>div.row
            [:div.one.wide.center.aligned.column
             [:div.ui.fluid.labeled.center.aligned.button
              [:i.fitted.center.aligned
               {:class (str (if active? "down" "right")
                            " chevron icon")
                :style {:width "100%"}}]]]
            [:div.fifteen.wide.column
             {:style {:padding-left "0"}}
             [:span.article-title primary-title]]]]
          [:div.two.wide.right.aligned.column.article-updated-time
           (when (and updated-time (not= updated-time 0))
             [ui/updated-time-label
              (util/time-from-epoch updated-time) true])]]]]
       [:div.ui.three.wide.center.aligned.middle.aligned.column.article-answers
        (when (not-empty labels)
          {:class answer-class})
        (when (not-empty labels)
          [:div.ui.middle.aligned.grid>div.row>div.column
           [AnswerCell article-id overall-labels answer-class]])]]
      ;; mobile view
      [:div.ui.row
       [:div.ui.eleven.wide.column.article-title
        [:span.article-title primary-title]
        (when (and updated-time (not= updated-time 0))
          [ui/updated-time-label
           (util/time-from-epoch updated-time) true])]
       [:div.ui.five.wide.center.aligned.middle.aligned.column.article-answers
        (when (not-empty labels)
          {:class answer-class})
        (when (not-empty labels)
          [:div.ui.middle.aligned.grid>div.row>div.column
           [AnswerCell article-id overall-labels answer-class]])]])))

(defn- ArticleListContent [context]
  (let [{:keys [recent-article active-article]}
        @(subscribe [::get (cached context)])
        project-id @(subscribe [:active-project-id])
        articles @(sub-articles (cached context))
        full-size? (util/full-size?)]
    [:div.ui.segments.article-list-segments
     (doall
      (concat
       (list
        ^{:key :article-nav-header}
        [ArticleListNavHeader context])
       (->>
        articles
        (map
         (fn [{:keys [article-id] :as article}]
           (let [recent? (= article-id recent-article)
                 active? (= article-id active-article)
                 have? @(subscribe [:have? [:article project-id article-id]])
                 classes (if (or active? recent?) "active" "")
                 loading? (loading/item-loading? [:article project-id article-id])
                 {:keys [next-id prev-id]}
                 @(subscribe [::prev-next-article-ids context])
                 go-next
                 (when next-id
                   #(dispatch-sync [::set-active-article context next-id]))
                 go-prev
                 (when prev-id
                   #(dispatch-sync [::set-active-article context prev-id]))]
             (doall
              (list
               [:a.ui.middle.aligned.grid.segment.article-list-article
                {:key [:list-row article-id]
                 :class (str (if recent? "active" ""))
                 :on-click
                 (util/wrap-user-event
                  (if active?
                    #(dispatch-sync [::set-active-article context nil])
                    #(dispatch-sync [::set-active-article context article-id])))}
                [ArticleListEntry (cached context) article full-size?]]
               (when active?
                 (doall
                  (list
                   #_
                   [:div.ui.middle.aligned.segment.no-padding.article-list-article-nav
                    {:key [:article-nav article-id]}
                    [:div.ui.fluid.buttons
                     [:a.ui.center.aligned.icon.button
                      {:on-click (util/wrap-user-event go-prev)
                       :class (if go-prev nil "disabled")}
                      [:i.left.chevron.icon]]
                     [:a.ui.center.aligned.icon.button
                      {:on-click (util/wrap-user-event go-next)
                       :class (if go-next nil "disabled")}
                      [:i.right.chevron.icon]]]]
                   [:div.ui.middle.aligned.grid.segment.article-list-full-article
                    {:key [:article-row article-id]
                     :class (str (if recent? "active" "")
                                 " "
                                 (if loading? "article-loading" ""))}
                    (when (and loading? (not have?))
                      [:div.ui.active.inverted.dimmer
                       [:div.ui.loader]])
                    [ArticleContent (cached context) article-id]])))))))))))]))

(defn- ArticleListExpandedEntry [context article]
  (let [base-context (no-cache context)
        {:keys [article-id]} article
        project-id @(subscribe [:active-project-id])
        {:keys [recent-article active-article]} @(subscribe [::get context])
        recent? (= article-id recent-article)
        active? (= article-id active-article)
        have? @(subscribe [:have? [:article project-id article-id]])
        classes (if (or active? recent?) "active" "")
        loading? (loading/item-loading? [:article project-id article-id])
        full-size? (util/full-size?)]
    (doall
     (list
      [:a.ui.middle.aligned.grid.segment.article-list-article
       {:key [:list-row article-id]
        :class (str (if recent? "active" ""))
        :on-click
        (util/wrap-user-event
         (if active?
           #(dispatch-sync [::set-active-article base-context nil])
           #(dispatch-sync [::set-active-article base-context article-id])))}
       [ArticleListEntry context article full-size?]]
      (when active?
        [:div.ui.middle.aligned.grid.segment.article-list-full-article
         {:key [:article-row article-id]
          :class (str (if recent? "active" "")
                      " "
                      (if loading? "article-loading" ""))}
         (when (and loading? (not have?))
           [:div.ui.active.inverted.dimmer
            [:div.ui.loader]])
         [ArticleContent context article-id]])))))

(defn- SingleArticlePanel [context]
  (let [active-article @(subscribe [::get context [:active-article]])
        project-id @(subscribe [:active-project-id])
        title @(subscribe [:article/title active-article])]
    (with-loader [[:article project-id active-article]] {}
      [:div>div.article-list-view
       [:div.ui.segments.article-list-segments
        (ArticleListExpandedEntry
         context {:article-id active-article
                  :primary-title title})]])))

(defn- require-all-data [context]
  (dispatch [:require @(subscribe [::count-query context])])
  (dispatch [:require @(subscribe [::articles-query context])])
  (when (not-empty @(subscribe [::ready-state context]))
    (dispatch [:require @(subscribe [::count-query (cached context)])])
    (dispatch [:require @(subscribe [::articles-query (cached context)])])))

(defn- MultiArticlePanel [context]
  (let [ready-state @(subscribe [::ready-state context])
        expand-filters @(subscribe [::display-options
                                    (cached context) :expand-filters])
        count-item (subscribe [::count-query (cached context)])
        data-item (subscribe [::articles-query (cached context)])
        active-article @(subscribe [::get (cached context) [:active-article]])]
    [:div.article-list-view
     (update-ready-state context)
     (with-loader [@count-item @data-item] {}
       (if (util/desktop-size?)
         [:div.ui.grid.article-list-grid
          [:div.row
           [:div.column.filters-column
            {:class (if (or active-article (not expand-filters))
                      "one wide" "four wide")}
            [ArticleListFiltersColumn context]]
           [:div.column.content-column
            {:class (if (or active-article (not expand-filters))
                      "fifteen wide" "twelve wide")}
            [:div.ui.form
             [:div.field>div.fields>div.sixteen.wide.field
              [TextSearchInput context]]]
            [ArticleListContent context]]]]
         [:div
          [ArticleListFiltersRow context]
          [ArticleListContent context]]))]))

(defn ArticleListPanel [context]
  [:div.article-list-toplevel-new
   (require-all-data context)
   [MultiArticlePanel context]])

#_
(defn ArticleListPanel [context]
  (let [articles @(sub-articles (cached context))
        active-article @(subscribe [::get (cached context) [:active-article]])
        visible-ids (map :article-id articles)]
    [:div.article-list-toplevel-new
     (require-all-data context)
     (if (and active-article (not (in? visible-ids active-article)))
       [SingleArticlePanel context]
       [MultiArticlePanel context])]))

(when use-new-article-list?
  ;; Gets id of article currently being individually displayed
  (reg-sub
   :article-list/article-id
   (fn [[_ context]]
     [(subscribe [:active-panel])
      (subscribe [::get context [:active-article]])])
   (fn [[active-panel article-id] [_ context]]
     (when (= active-panel (:panel context))
       article-id)))

  (reg-event-fx
   :article-list/set-recent-article
   [trim-v]
   (fn [{:keys [db]} [context article-id]]
     {:db (set-state db context [:recent-article] article-id)}))

  (reg-event-fx
   :article-list/set-active-article
   [trim-v]
   (fn [{:keys [db]} [context article-id]]
     {:dispatch [::set-active-article context article-id]}))

  (reg-sub
   :article-list/editing?
   (fn [[_ context article-id]]
     [(subscribe [::get context [:active-article]])
      (subscribe [::editing-allowed? context article-id])
      (subscribe [:article/user-status article-id])
      (subscribe [::private-view? context])
      (subscribe [:review/change-labels? article-id (:panel context)])])
   (fn [[active-id can-edit? user-status private-view? change-labels?]
        [_ context article-id]]
     (when (= article-id active-id)
       (boolean
        (and can-edit?
             (or change-labels?
                 (and private-view?
                      (= user-status :unconfirmed))))))))

  (reg-sub
   :article-list/resolving?
   (fn [[_ context article-id]]
     [(subscribe [:article-list/editing? context article-id])
      (subscribe [::resolving-allowed? context article-id])])
   (fn [[editing? resolving-allowed?]]
     (boolean
      (and editing? resolving-allowed?)))))
