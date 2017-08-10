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
  [name & {:keys [uri method content process] :as fields}]
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

(defn- any-running-impl [counts]
  (boolean
   (some (fn [item]
           (> (get-in counts [:sent item] 0)
              (get-in counts [:returned item] 0)))
         (keys (get-in counts [:sent])))))

(defn any-action-running? [db]
  (let [counts (get-in db [:ajax :action])]
    (any-running-impl counts)))

;; Tests if any AJAX action is currently pending
(reg-sub
 :action/any-running?
 :<- [::ajax-action-counts]
 (fn [counts]
   (any-running-impl counts)))

;; Runs an AJAX action specified by `item`
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
              {:db db
               :method (or (:method entry) :post)
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
