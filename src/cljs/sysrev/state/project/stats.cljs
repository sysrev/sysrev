(ns sysrev.state.project.stats
  (:require [re-frame.core :refer [subscribe reg-sub]]))

(reg-sub ::stats
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:stats %))

(reg-sub :project/article-counts
         (fn [[_ project-id]] (subscribe [::stats project-id]))
         (fn [stats]
           (let [total (-> stats :articles)
                 reviewed (-> stats :status-counts :reviewed)]
             {:total total
              :reviewed reviewed
              :unreviewed (- total reviewed)})))

(reg-sub :project/status-counts
         (fn [[_ project-id]] (subscribe [::stats project-id]))
         #(:status-counts %))

(reg-sub :project/labeled-counts
         (fn [[_ project-id]] (subscribe [:project/status-counts project-id]))
         (fn [counts]
           (let [get-count   #(get counts % 0)
                 single      (+ (get-count [:single true])
                                (get-count [:single false]))
                 consistent  (+ (get-count [:consistent true])
                                (get-count [:consistent false]))
                 resolved    (+ (get-count [:resolved true])
                                (get-count [:resolved false]))
                 conflict    (get-count [:conflict nil])]
             {:single single
              :consistent consistent
              :resolved resolved
              :conflict conflict})))

(reg-sub :project/progress-counts
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(-> % :stats :progress))

(reg-sub :project/predict
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(-> % :stats :predict))

(reg-sub :predict/labeled-count
         (fn [[_ project-id]] (subscribe [:project/predict project-id]))
         #(-> % :counts :labeled))

(reg-sub :predict/article-count
         (fn [[_ project-id]] (subscribe [:project/predict project-id]))
         #(-> % :counts :total))

(reg-sub :predict/update-time
         (fn [[_ project-id]] (subscribe [:project/predict project-id]))
         #(:update-time %))
