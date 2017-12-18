(ns sysrev.data.core
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
     reg-event-db reg-event-fx trim-v reg-fx]]
   [re-frame.db :refer [app-db]]
   [reagent.ratom :refer [reaction]]
   [sysrev.action.core :refer [any-action-running?]]
   [sysrev.subs.core :refer [not-found-value try-get]]
   [sysrev.events.ajax :refer
    [reg-event-ajax reg-event-ajax-fx run-ajax]]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.shared.util :refer [in?]]))

(defonce
  ^{:doc "Holds static definitions for data items fetched from server"}
  data-defs (atom {}))

(defn def-data
  "Create definition for a data item to fetch from server."
  [name & {:keys [prereqs loaded? uri content process] :as fields}]
  (swap! data-defs assoc name fields))

;; Gets raw list of data requirements
(defn- get-needed-raw [db] (get db :needed []))

;; Adds an item to list of requirements
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
     (cond-> {:db new-db
              :dispatch-n (->> prereqs (map (fn [x] [:require x])))}
       changed? (merge {::fetch-if-missing item})))))

(defn- add-load-trigger [db item trigger-id action]
  (assoc-in db [:on-load item trigger-id] action))

(defn- remove-load-triggers [db item]
  (dissoc-in db [:on-load item]))

(defn- lookup-load-triggers [db item]
  (get-in db [:on-load item]))

(reg-event-db
 :data/after-load
 [trim-v]
 (fn [db [item trigger-id action]]
   (add-load-trigger db item trigger-id action)))

(reg-event-fx
 ::process-load-triggers
 [trim-v]
 (fn [{:keys [db]} [item]]
   (let [actions (vals (lookup-load-triggers db item))]
     (doseq [action actions]
       (cond (vector? action)  nil
             (fn? action)      (action)
             (seq? action)     (doseq [subaction action]
                                 (when (fn? subaction)
                                   (subaction)))))
     {:db (remove-load-triggers db item)
      :dispatch-n (->> actions
                       (map #(cond (vector? %)  (list %)
                                   (seq? %)     (filter vector? %)
                                   (fn? %)      nil))
                       (apply concat))})))

(reg-fx
 ::process-load-triggers
 (fn [item]
   (dispatch [::process-load-triggers item])))

(reg-event-db
 :data/reset-required
 (fn [db]
   (assoc db :needed [])))

;; Tests if `item` is loaded
(defn- have-item? [db item]
  (let [[name & args] item
        {:keys [loaded?] :as entry} (get @data-defs name)]
    (apply loaded? db args)))
(reg-sub :have? (fn [db [_ item]] (have-item? db item)))

;; Returns list of required items with no missing prerequisites
(defn- get-needed-items [db]
  (->> (get-needed-raw db)
       (mapv (fn [[prereqs item]]
               (when (every? #(have-item? db %) prereqs)
                 item)))
       (filterv (comp not nil?))))

;; Returns list of required items that are not yet loaded
(defn- get-missing-items [db]
  (->> (get-needed-items db)
       (remove (partial have-item? db))
       vec))
(reg-sub ::missing get-missing-items)

;; Tests whether all required data has been loaded
(reg-sub
 :data/ready?
 :<- [:initialized?]
 :<- [::missing]
 (fn [[initialized? missing]]
   (boolean (and initialized? (empty? missing)))))

;;
;; Maintain counters for start/completion of AJAX requests
;;

(reg-event-db
 ::sent
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :data :sent item]
              #(-> % (or 0) inc))))
(reg-fx ::sent (fn [item] (dispatch [::sent item])))

(reg-event-db
 ::returned
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :data :returned item]
              #(-> % (or 0) inc))))
(reg-fx ::returned (fn [item] (dispatch [::returned item])))

(reg-sub ::ajax-data-counts (fn [db] (get-in db [:ajax :data])))

(defn- get-sent-count [db item]
  (get-in db [:ajax :data :sent item] 0))
(reg-sub ::sent-count (fn [db [_ item]] (get-sent-count db item)))

(defn- get-returned-count [db item]
  (get-in db [:ajax :data :returned item] 0))
(reg-sub ::returned-count (fn [db [_ item]] (get-returned-count db item)))

;; Tests if an AJAX request for `item` is currently pending
(defn- item-loading? [db item]
  (> (get-sent-count db item)
     (get-returned-count db item)))
(reg-sub :loading? (fn [db [_ item]] (item-loading? db item)))

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

;; TODO: replace this with a queue for items to fetch in @app-db
(defonce
  ^{:doc "Atom used to ensure a minimal delay between :fetch events."}
  last-fetch-millis (atom 0))

;; Fetch data item from server, unless identical request is pending.
;;
;; Usually this should be triggered from :require via `with-loader`,
;; or from :reload to fetch updated data in response to a user action.
(reg-event-fx
 :fetch
 [trim-v]
 (fn [{:keys [db]} [item]]
   (let [[name & args] item
         entry (get @data-defs name)
         elapsed-millis (- (js/Date.now) @last-fetch-millis)]
     (when entry
       (cond (< elapsed-millis 25)
             (do (js/setTimeout #(dispatch [:fetch item])
                                (- 30 elapsed-millis))
                 {})
             (any-action-running? db)
             (do (js/setTimeout #(dispatch [:fetch item])
                                50)
                 {})
             :else
             (when-not (item-loading? db item)
               (reset! last-fetch-millis (js/Date.now))
               (let [uri (apply (:uri entry) args)
                     content (some-> (:content entry) (apply args))]
                 (merge
                  {::sent item}
                  (run-ajax
                   (cond->
                       {:db db
                        :method :get
                        :uri uri
                        :on-success [::on-success [name args]]
                        :on-failure [::on-failure [name args]]}
                     content (assoc :content content)))))))))))

(reg-event-ajax-fx
 ::on-success
 (fn [{:keys [db] :as cofx} [[name args] result]]
   (let [item (vec (concat [name] args))]
     (merge
      {::returned item}
      (when-let [entry (get @data-defs name)]
        (when-let [process (:process entry)]
          (merge (apply process [cofx args result])
                 ;; Run :fetch-missing in case this request provided any
                 ;; missing data prerequisites.
                 {:fetch-missing true}
                 (when (not-empty (lookup-load-triggers db item))
                   {::process-load-triggers item}))))))))

(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [[name args] result]]
   (let [item (vec (concat [name] args))]
     {::returned item})))

;; Reload data item from server if already loaded.
(reg-event-fx
 :reload
 [trim-v]
 (fn [{:keys [db]} [item]]
   (when (and (have-item? db item)
              (not (item-loading? db item)))
     {:dispatch [:fetch item]})))

;; Fetches any missing required data
(reg-event-fx
 :fetch-missing
 (fn [{:keys [db]}]
   {:dispatch-n
    (map (fn [item] [:fetch item])
         (get-missing-items db))}))

(reg-fx
 ::fetch-if-missing
 (fn [item]
   (js/setTimeout
    #(let [missing (get-missing-items @app-db)]
       (when (and item (in? missing item))
         (dispatch [:fetch item])))
    10)))

(reg-fx
 :fetch-missing
 (fn [fetch?]
   (when fetch?
     ;; Use setTimeout to ensure that changes to app-db from any simultaneously
     ;; dispatched events will have completed first.
     (js/setTimeout #(dispatch [:fetch-missing]) 30))))

;; (Re-)fetches all required data. This shouldn't be needed.
(reg-event-fx
 :data/fetch-all
 (fn [{:keys [db]}]
   {:dispatch-n
    (map (fn [item] [:fetch item])
         (get-needed-items db))}))

(defn init-data []
  (dispatch [:ui/load-default-panels])
  (dispatch [:fetch [:identity]]))
