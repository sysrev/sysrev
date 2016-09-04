(ns sysrev-web.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]
            [cljs.pprint :refer [pprint]]))

(secretary/set-config! :prefix "")

(defonce history
  (pushy/pushy secretary/dispatch!
               (fn [x] (when (secretary/locate-route x) x))))

(defn history-init []
  (pushy/start! history))

(defonce server-data (r/atom {}))

(defonce state
  (r/atom {;; State specific to current page
           :page {}
           ;; Independent app-wide state for popup notifications
           :notifications #queue []
           ;; Independent app-wide state for holding state of the classify queue
           ;;;; FIFO for upcoming articles
           :label-activity #queue []
           ;;;; LIFO for skipped articles
           :label-skipped '()}))

(defn current-page []
  (-> @state :page keys first))

(defn on-page?
  "Check whether the current page is `page-key`.
  The current page is contained in (:page @state) which is a map with a
  single element, the key of which identifies the page."
  [page-key]
  (contains? (:page @state) page-key))

(defn current-user-id []
  (-> @state :identity :id))

(defn logged-in? []
  (integer? (current-user-id)))
