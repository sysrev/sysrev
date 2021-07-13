(ns sysrev.reviewer-time.core
  (:require [sysrev.db.core :as db]))

(defn create-events! [connectable events]
  (->> events
       (map #(select-keys % #{:article-id :event-type :project-id :user-id}))
       (hash-map :insert-into :reviewer-event :values)
       (db/execute! connectable)))
