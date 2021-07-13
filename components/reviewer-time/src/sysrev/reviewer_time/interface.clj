(ns sysrev.reviewer-time.interface
  (:require [sysrev.reviewer-time.core :as reviewer-time]))

(defn create-events!
  "Record reviewer events. Each event should be a map with the keys
  [:article-id :event-type :project-id :user-id]."
  [connectable events]
  (reviewer-time/create-events! connectable events))
