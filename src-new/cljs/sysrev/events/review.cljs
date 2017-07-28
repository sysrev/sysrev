(ns sysrev.events.review
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx trim-v reg-fx]]
   [sysrev.subs.labels :refer [get-label-raw]]
   [sysrev.subs.review :as review]))

(reg-event-db
 :review/load-task
 [trim-v]
 (fn [db [article-id today-count]]
   (-> db
       (assoc-in [:data :review :task-id] article-id)
       (assoc-in [:data :review :today-count] today-count))))

(defn set-label-value [db article-id label-id label-value]
  (assoc-in db [:state :review :labels article-id label-id]
            label-value))

(reg-event-fx
 :review/set-label-value
 [trim-v]
 (fn [{:keys [db]} [article-id label-id label-value]]
   {:db (set-label-value db article-id label-id label-value)}))

(reg-event-fx
 :review/enable-label-value
 [trim-v]
 (fn [{:keys [db]} [article-id label-id label-value]]
   (let [{:keys [value-type]} (get-label-raw db label-id)]
     {:db (condp = value-type
            "boolean" (set-label-value db article-id label-id label-value)
            db)
      :review/enable-label-value-ui [article-id label-id label-value value-type]})))

(reg-fx
 :review/enable-label-value-ui
 (fn [[article-id label-id label-value value-type]]
   (condp = value-type
     "boolean"      nil
     ;; TODO: add this ui component
     "categorical"  (.dropdown
                     (js/$ (str "#label-edit-" article-id "-" label-id))
                     "set selected"
                     label-value))))

(reg-event-fx
 :review/send-labels
 [trim-v]
 (fn [{:keys [db]} [{:keys [article-id confirm? resolve?]}]]
   (let [label-values (review/active-labels db article-id)]
     {:dispatch [:action [:send-labels {:article-id article-id
                                        :label-values label-values
                                        :confirm? confirm?
                                        :resolve? resolve?}]]})))
