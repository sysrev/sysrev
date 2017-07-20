(ns sysrev.action.core
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx
     trim-v reg-fx]]
   [sysrev.events.ajax :refer
    [reg-event-ajax reg-event-ajax-fx run-ajax]]))

(defonce
  ^{:doc "Holds static definitions for server request actions"}
  action-defs (atom {}))

(defn def-action
  "Creates definition for a server request action."
  [name & {:keys [uri content process method] :as fields}]
  (swap! action-defs assoc name fields))

;; Maintain counters for start/completion of AJAX data requests
(reg-event-db
 ::sent
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :action :sent item]
              #(-> % (or 0) inc))))
(reg-event-db
 ::returned
 [trim-v]
 (fn [db [item]]
   (update-in db [:ajax :action :returned item]
              #(-> % (or 0) inc))))
(reg-fx
 ::sent
 (fn [item] (dispatch [::sent item])))
(reg-fx
 ::returned
 (fn [item] (dispatch [::returned item])))
(reg-sub
 ::ajax-action-counts
 (fn [db] (get-in db [:ajax :action])))
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

;; Tests if any AJAX data request is currently pending
(reg-sub
 :action/any-running?
 :<- [::ajax-action-counts]
 (fn [counts]
   (boolean
    (some (fn [item]
            (> (get-in counts [:sent item] 0)
               (get-in counts [:returned item] 0)))
          (keys (get-in counts [:sent]))))))

(reg-event-fx
 :action
 [trim-v]
 (fn [{:keys [db]} [item]]
   (let [[name & args] item
         entry (get @action-defs name)]
     (when entry
       (let [uri (apply (:uri entry) args)
             content (some-> (:content entry) (apply args))]
         (run-ajax
          (cond->
              {:method (or (:method entry) :post)
               :uri uri
               :on-success [::on-success [name args]]
               :on-failure [::on-failure [name args]]}
            content (assoc :content content))))))))

(reg-event-ajax-fx
 ::on-success
 (fn [cofx [[name args] result]]
   (let [item (vec (concat [name] args))]
     (merge
      {::returned item}
      (when-let [entry (get @action-defs name)]
        (when-let [process (:process entry)]
          (apply process [cofx args result])))))))

(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [[name args] result]]
   (let [item (vec (concat [name] args))]
     {::returned item})))
