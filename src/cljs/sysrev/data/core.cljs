(ns sysrev.data.core
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch dispatch-sync reg-sub reg-sub-raw
     reg-event-db reg-event-fx trim-v reg-fx]]
   [re-frame.db :refer [app-db]]
   [reagent.ratom :refer [reaction]]
   [sysrev.action.core :refer [any-action-running?]]
   [sysrev.ajax :refer
    [reg-event-ajax reg-event-ajax-fx run-ajax]]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.shared.util :refer [in?]]))

(defonce
  ^{:doc "Holds static definitions for data items fetched from server"}
  data-defs (atom {}))

(defn def-data
  "Create definition for a data item to fetch from server."
  [name & {:keys [prereqs loaded? uri content content-type process on-error]
           :as fields}]
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

(reg-fx
 :data/reset-required (fn [_] (dispatch [:data/reset-required])))

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
   (let [time-ms (js/Date.now)]
     (-> db
         (update-in [:ajax :data :sent item]
                    #(-> % (or 0) inc))
         (update-in [:ajax :data :timings item]
                    #(->> (concat [time-ms] %)
                          (take 10)))))))

(reg-fx ::sent (fn [item] (dispatch [::sent item])))

(reg-event-db
 ::returned
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :data :returned item]
              #(-> % (or 0) inc))))

(reg-event-fx
 ::failed
 [trim-v]
 (fn [{:keys [db]} [item]]
   (let [time-ms (js/Date.now)]
     {:db (assoc-in db [:ajax :data :failed item] true)
      :data/reset-required true})))

(reg-fx ::failed (fn [item] (dispatch [::failed item])))

(reg-event-db
 ::reset-failed
 [trim-v]
 (fn [db [item]]
   (assoc-in db [:ajax :data :failed item] false)))

(reg-fx ::reset-failed (fn [item] (dispatch [::reset-failed item])))

(defn- item-failed? [db item]
  (true? (get-in db [:ajax :data :failed item])))

;; Returns the time (ms) of 4th most recent fetch of item
(defn- get-spam-time [db item]
  (->> (get-in db [:ajax :data :timings item])
       (drop 4)
       first))

;; Checks if item has been fetched 5 times within last 2.5s
(defn- item-spammed? [db item]
  (let [spam-ms (get-spam-time db item)]
    (if (nil? spam-ms)
      false
      (let [now-ms (js/Date.now)]
        (< (- now-ms spam-ms) 2500)))))

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
     (when (and entry (not (item-loading? db item)))
       (cond (item-spammed? db item)
             {::failed item}

             (< elapsed-millis 25)
             (do (js/setTimeout #(dispatch [:fetch item])
                                (- 30 elapsed-millis))
                 {})

             (any-action-running? db nil [:sources/delete])
             (do (js/setTimeout #(dispatch [:fetch item])
                                50)
                 {})

             :else
             (let [uri (apply (:uri entry) args)
                   content (some-> (:content entry) (apply args))
                   content-type (or (:content-type entry)
                                    "application/transit+json")]
               (reset! last-fetch-millis (js/Date.now))
               (merge
                {::sent item}
                (run-ajax
                 (cond->
                     {:db db
                      :method :get
                      :uri uri
                      :on-success [::on-success item]
                      :on-failure [::on-failure item]
                      :content-type content-type}
                   content (assoc :content content))))))))))

(reg-event-ajax-fx
 ::on-success
 (fn [{:keys [db] :as cofx} [item result]]
   (let [[name & args] item
         spammed? (item-spammed? db item)]
     (merge
      {::returned item}
      (if spammed?
        {::failed item}
        {::reset-failed item})
      (when (not spammed?)
        (when-let [entry (get @data-defs name)]
          (when-let [process (:process entry)]
            (merge (apply process [cofx args result])
                   ;; Run :fetch-missing in case this request provided any
                   ;; missing data prerequisites.
                   {:fetch-missing true}
                   (when (not-empty (lookup-load-triggers db item))
                     {::process-load-triggers item})))))))))

(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge
      {::returned item
       ::failed item}
      (when-let [entry (get @data-defs name)]
        (when-let [process (:on-error entry)]
          (apply process [cofx args result])))))))

;; Reload data item from server if already loaded.
(reg-event-fx
 :reload
 [trim-v]
 (fn [{:keys [db]} [item]]
   (when (and (or (have-item? db item)
                  (item-failed? db item))
              (not (item-loading? db item)))
     {:dispatch [:fetch item]})))

;; Fetches any missing required data
(reg-event-fx
 :fetch-missing
 (fn [{:keys [db]}]
   {:dispatch-n
    (->> (get-missing-items db)
         (remove #(item-failed? db %))
         (map (fn [item] [:fetch item])))}))

(reg-fx
 ::fetch-if-missing
 (fn [item]
   (js/setTimeout
    #(let [missing (get-missing-items @app-db)]
       (when (and item (in? missing item)
                  (not (item-failed? @app-db item)))
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
