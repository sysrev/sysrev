(ns sysrev.action.core
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx
     trim-v reg-fx]]
   [sysrev.events.ajax :refer
    [reg-event-ajax reg-event-ajax-fx run-ajax]]
   [sysrev.shared.util :refer [in?]]))

(defonce
  ^{:doc "Holds static definitions for server request actions"}
  action-defs (atom {}))

(defn def-action
  "Creates definition for a server request action."
  [name & {:keys [uri method content process content-type on-error] :as fields}]
  (swap! action-defs assoc name fields))

;;
;; Maintain counters for start/completion of AJAX requests
;;

(reg-event-db
 ::sent
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :action :sent item]
              #(-> % (or 0) inc))))
(reg-fx ::sent (fn [item] (dispatch [::sent item])))

(reg-event-db
 ::returned
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :action :returned item]
              #(-> % (or 0) inc))))
(reg-fx ::returned (fn [item] (dispatch [::returned item])))

(reg-sub ::ajax-action-counts (fn [db] (get-in db [:ajax :action])))

(reg-sub
 ::sent-count
 :<- [::ajax-action-counts]
 (fn [counts [_ item]]
   (get-in counts [:sent item] 0)))

(reg-sub
 ::returned-count
 :<- [::ajax-action-counts]
 (fn [counts [_ item]]
   (get-in counts [:returned item] 0)))

;; Tests if an AJAX request for `item` is currently pending
(reg-sub
 :action/running?
 (fn [[_ item]]
   [(subscribe [::sent-count item])
    (subscribe [::returned-count item])])
 (fn [[sent-count returned-count]]
   (> sent-count returned-count)))

(defn- any-running-impl
  [counts & [filter-item-name ignore-item-names]]
  (boolean
   (->> (keys (get-in counts [:sent]))
        (filter #(or (nil? filter-item-name)
                     (= (first %) filter-item-name)))
        (filter #(not (in? ignore-item-names (first %))))
        (some #(> (get-in counts [:sent %] 0)
                  (get-in counts [:returned %] 0))))))

(defn any-action-running?
  [db & [filter-item-name ignore-item-names]]
  (let [counts (get-in db [:ajax :action])]
    (any-running-impl counts filter-item-name ignore-item-names)))

;; Tests if any AJAX action is currently pending
;; If filter-item-name is passed, only test for actions which have that name
(reg-sub
 :action/any-running?
 :<- [::ajax-action-counts]
 (fn [counts [_ filter-item-name ignore-item-names]]
   (any-running-impl counts filter-item-name ignore-item-names)))

;; Runs an AJAX action specified by `item`
(reg-event-fx
 :action
 [trim-v]
 (fn [{:keys [db]} [item]]
   (let [[name & args] item
         entry (get @action-defs name)]
     (when entry
       (let [uri (apply (:uri entry) args)
             content (some-> (:content entry) (apply args))
             content-type (or (:content-type entry)
                              "application/transit+json")]
         (merge
          (run-ajax
           (cond->
               {:db db
                :method (or (:method entry) :post)
                :uri uri
                :on-success [::on-success item]
                :on-failure [::on-failure item]
                :content-type content-type}
             content (assoc :content content)))
          {::sent item}) )))))

(reg-event-ajax-fx
 ::on-success
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge
      {::returned item}
      (when-let [entry (get @action-defs name)]
        (when-let [process (:process entry)]
          (apply process [cofx args result])))))))

(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge
      {::returned item}
      (when-let [entry (get @action-defs name)]
        (when-let [process (:on-error entry)]
          (apply process [cofx args result])))))))

#_(reg-event-ajax-fx
   ::on-failure
   (fn [cofx [[name args] result]]
     (let [item (vec (concat [name] args))]
       {::returned item})))
