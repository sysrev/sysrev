(ns sysrev-web.state.core
  (:require [sysrev-web.base :refer [state]]
            [reagent.core :as r]))

(defn init-state []
  (reset! state {;; State specific to current page
                 :page {}
                 ;; Data pulled from server
                 :data {}
                 ;; Independent app-wide state for popup notifications
                 :notifications #queue []}))

(defn current-page []
  (-> @state :active-page))

(defn on-page?
  "Check whether the current page is `page-key`.
  The current page is contained in (:page @state) which is a map with a
  single element, the key of which identifies the page."
  [page-key]
  (= (current-page) page-key))

(defn current-user-id []
  (-> @state :identity :id))

(defn logged-in? []
  (integer? (current-user-id)))

(defn page-state
  ([& ks]
   (get-in @state (concat [:page (current-page)] ks))))

(defn set-identity [imap]
  (fn [s]
    (assoc s :identity imap)))

(defn log-out []
  (fn [s]
    (-> s
        (assoc :identity nil)
        (assoc :data {})
        (assoc :page {}))))

(defn set-classify-task [article-id]
  (fn [s]
    (-> s
        (assoc-in [:data :classify-article-id] article-id)
        (assoc-in [:page :classify :label-values] {}))))
