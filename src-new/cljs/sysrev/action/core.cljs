(ns sysrev.action.core
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-sub-raw reg-event-db reg-event-fx
     trim-v reg-fx]]
   [sysrev.events.ajax :refer
    [reg-event-ajax reg-event-ajax-fx run-ajax]]))

;; Holds static definitions for server actions
(defonce action-defs (atom {}))

;; Create definition for a server action
(defn def-action [name & {:keys [uri content-spec process] :as fields}]
  (swap! action-defs assoc name fields))
