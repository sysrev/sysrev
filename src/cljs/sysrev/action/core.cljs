(ns sysrev.action.core
  (:require [re-frame.core :refer [subscribe dispatch reg-event-db reg-event-fx trim-v reg-fx]]
            [sysrev.ajax :refer [reg-event-ajax reg-event-ajax-fx run-ajax]]))

(defonce
  ^{:doc "Holds static definitions for server request actions"}
  action-defs (atom {}))

(defn def-action
  "Creates definition for a server request action."
  [name & {:keys [uri method content process content-type on-error] :as fields}]
  (swap! action-defs assoc name fields))

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
          {:action-sent item}) )))))

(reg-event-ajax-fx
 ::on-success
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge
      {:action-returned item}
      (when-let [entry (get @action-defs name)]
        (when-let [process (:process entry)]
          (apply process [cofx args result])))))))

(reg-event-ajax-fx
 ::on-failure
 (fn [cofx [item result]]
   (let [[name & args] item]
     (merge
      {:action-returned item}
      (when-let [entry (get @action-defs name)]
        (when-let [process (:on-error entry)]
          (apply process [cofx args result])))))))

#_(reg-event-ajax-fx
   ::on-failure
   (fn [cofx [[name args] result]]
     (let [item (vec (concat [name] args))]
       {::returned item})))
