(ns sysrev.reviewer-time.interface
  (:require [sysrev.reviewer-time.core :as reviewer-time]))

(defn create-events!
  "Record reviewer events. Each event should be a map with the keys
  [:article-id :event-type :project-id :user-id]."
  [connectable events]
  (reviewer-time/create-events! connectable events))

(defn to-intervals
  "Takes a seq of events, all for a single user, and returns a seq of
  maps representing time intervals spent on each article. Each returned map
  has keys [:article-id :project-id :start :end]."
  [events]
  (reviewer-time/to-intervals events))
