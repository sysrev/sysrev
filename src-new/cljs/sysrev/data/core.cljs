(ns sysrev.data.core
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx
     trim-v reg-fx]]
   [re-frame.db :refer [app-db]]
   [reagent.ratom :refer [reaction]]
   [sysrev.subs.core :refer [not-found-value try-get]]
   [sysrev.events.ajax :refer
    [reg-event-ajax reg-event-ajax-fx run-ajax]]))

;; Holds static definitions for data items fetched from server
(defonce data-defs (atom {}))

;; Create definition for a data item
(defn def-data [name & {:keys [prereqs sub uri process] :as fields}]
  (swap! data-defs assoc name fields))

(defn- get-req-sub [item]
  (let [[name & args] (if (sequential? item) item [item])]
    (if-let [sub (get-in @data-defs [name :sub])]
      (apply sub args)
      item)))

(defn- have-value? [val]
  (boolean
   (and (not (nil? val))
        (not= not-found-value val))))

(defn- have-item? [item]
  (have-value? @(subscribe [::value item])))

(reg-sub
 ::needed
 (fn [db]
   (get db :needed [])))

;; Gets raw value of `item` from db to test if loaded
(reg-sub-raw
 ::value
 (fn [_ [_ item]]
   (reaction
    @(subscribe (get-req-sub item)))))

;; Tests if `item` is loaded
(reg-sub
 :have?
 (fn [[_ item]]
   [(subscribe [::value item])])
 (fn [[value] [_ item]]
   (have-value? value)))

;; Returns list of required items with no missing prerequisites
(reg-sub-raw
 :data/needed
 (fn [db _]
   (reaction
    (->> @(subscribe [::needed])
         (mapv (fn [[prereqs item]]
                 (when (every? have-item? prereqs)
                   item)))
         (filterv (comp not nil?))))))

(reg-sub-raw
 ::needed-values
 (fn [db _]
   (reaction
    (mapv #(subscribe [::value %])
          @(subscribe [:data/needed])))))

;; Returns list of required items that are not yet loaded
(reg-sub
 :data/missing
 :<- [:data/needed]
 :<- [::needed-values]
 (fn [[items values]]
   (->> (map vector items values)
        (remove #(have-value? @(second %)))
        (mapv first))))

;; Tests whether all required data has been loaded
(reg-sub
 :data/ready?
 :<- [:initialized?]
 :<- [:data/missing]
 (fn [[initialized? missing]]
   (boolean (and initialized? (empty? missing)))))

(defn- require-item
  ([db prereqs item]
   (update db :needed
           #(-> % vec (conj [(vec prereqs) item]) distinct))))

;; Register `item` as required; will trigger fetching from server
;; immediately or after any prerequisites for `item` have been loaded.
(reg-event-fx
 :require
 [trim-v]
 (fn [{:keys [db]} [item]]
   (let [[name & args] item
         entry (get @data-defs name)
         prereqs (some-> (:prereqs entry) (apply args))
         new-db (require-item db prereqs item)
         changed? (not= (:needed new-db) (:needed db))]
     (cond-> {:db new-db}
       changed? (merge {::fetch-missing true})))))

(reg-event-db
 :data/reset-required
 (fn [db]
   (assoc db :needed [])))

;; Maintain counters for start/completion of AJAX data requests
(reg-event-db
 ::sent
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :data :sent item]
              #(-> % (or 0) inc))))
(reg-event-db
 ::returned
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :data :returned item]
              #(-> % (or 0) inc))))
(reg-fx
 ::sent
 (fn [item]
   (dispatch [::sent item])))
(reg-fx
 ::returned
 (fn [item]
   (dispatch [::returned item])))
(reg-sub
 ::ajax-data-counts
 (fn [db]
   (get-in db [:ajax :data])))
(reg-sub
 ::sent-count
 :<- [::ajax-data-counts]
 (fn [counts [_ item]]
   (get-in counts [:sent item] 0)))
(reg-sub
 ::returned-count
 :<- [::ajax-data-counts]
 (fn [counts [_ item]]
   (get-in counts [:returned item] 0)))

;; Tests if an AJAX request for `item` is currently pending
(reg-sub
 :loading?
 (fn [[_ item]]
   [(subscribe [::sent-count item])
    (subscribe [::returned-count item])])
 (fn [[sent-count returned-count]]
   (> sent-count returned-count)))

;; Tests if any AJAX data request is currently pending
(reg-sub
 :any-loading?
 :<- [::ajax-data-counts]
 (fn [counts]
   (boolean
    (some (fn [item]
            (> (get-in counts [:sent item] 0)
               (get-in counts [:returned item] 0)))
          (keys (get-in counts [:sent]))))))

;; Fetch data item from server, unless identical request is pending.
;;
;; Usually this should be triggered from :require via `with-loader`,
;; or from :reload to fetch updated data in response to a user action.
(reg-event-fx
 :fetch
 [trim-v]
 (fn [_ [item]]
   (let [[name & args] item]
     (when-let [entry (get @data-defs name)]
       (when-not @(subscribe [:loading? item])
         (let [uri (apply (:uri entry) args)
               content (some-> (:content entry) (apply args))]
           (merge
            {::sent item}
            (run-ajax
             (cond->
                 {:method :get
                  :uri uri
                  :on-success [::on-success [name args]]
                  :on-failure [::on-failure [name args]]}
               content (assoc :content content))))))))))
(reg-event-ajax-fx
 ::on-success
 (fn [cofx [[name args] result]]
   (let [item (vec (concat [name] args))]
     (merge
      {::returned item}
      (when-let [entry (get @data-defs name)]
        (when-let [process (:process entry)]
          (merge (apply process [cofx args result])
                 ;; Run :fetch-missing in case this request provided
                 ;; any missing data prerequisites
                 {::fetch-missing true})))))))
(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [[name args] result]]
   (let [item (vec (concat [name] args))]
     (merge
      {::returned item}))))

;; Reload data item from server if already loaded.
(reg-event-fx
 :reload
 [trim-v]
 (fn [_ [item]]
   (when (and @(subscribe [:have? item])
              (not @(subscribe [:loading? item])))
     {:dispatch [:fetch item]})))

;; Fetches any missing required data
(reg-event-fx
 :fetch-missing
 (fn []
   {:dispatch-n
    (map (fn [item] [:fetch item])
         @(subscribe [:data/missing]))}))

;; re-frame effect to trigger :fetch-missing from event handlers
(reg-fx
 ::fetch-missing
 (fn [fetch?]
   (when fetch?
     ;; use setTimeout to ensure that changes to app-db from any
     ;; simultaneously dispatched events will have completed first
     (js/setTimeout #(dispatch [:fetch-missing]) 25))))

;; (Re-)fetches all required data. This shouldn't be needed.
(reg-event-fx
 :data/fetch-all
 (fn []
   {:dispatch-n
    (map (fn [item] [:fetch item])
         @(subscribe [:data/needed]))}))

(defn init-data []
  (dispatch [:fetch [:identity]]))
