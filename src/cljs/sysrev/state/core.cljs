(ns sysrev.state.core
  (:require [sysrev.base :refer [st st-if-exists]]
            [sysrev.shared.util :refer [in?]]
            [reagent.core :as r])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(defn data
  ([ks]
   (data ks nil))
  ([ks not-found]
   (let [ks (if (keyword? ks) [ks] ks)]
     (st-if-exists (concat [:data] ks) not-found))))

(defn current-page []
  (st :active-page))

(defn current-user-id []
  (st :identity :user-id))

(defn current-project-id []
  (st :current-project-id))

(defn logged-in? []
  (integer? (current-user-id)))

(defn page-state
  ([& ks]
   (apply st (concat [:page (current-page)] ks))))

(defn set-identity [imap]
  (fn [s]
    (assoc s :identity imap)))

(defn set-current-project-id [project-id]
  (fn [s]
    (assoc s :current-project-id project-id)))

(defn change-project [project-id]
  (fn [s]
    (-> s
        (assoc :current-project-id project-id)
        (assoc :data {}))))

(defn log-out []
  (fn [s]
    (-> s
        (assoc :identity nil)
        (assoc :current-project-id nil)
        (assoc :data {})
        (assoc :page {}))))

(defn set-classify-task [article-id review-status]
  (fn [s]
    (-> s
        (assoc-in [:data :classify-article-id] article-id)
        (assoc-in [:data :classify-review-status] review-status)
        (assoc-in [:page :classify :label-values] {}))))

(defn set-csrf-token [csrf-token]
  (fn [s]
    (assoc s :csrf-token csrf-token)))

(defn csrf-token []
  (using-work-state (st :csrf-token)))

(defn set-all-projects [pmap]
  (fn [s]
    (assoc-in s [:data :all-projects] pmap)))

(defn set-user-info [user-id umap]
  (fn [s]
    (assoc-in s [:data :users user-id] umap)))

(defn user [user-id & ks]
  (data (concat [:users user-id] ks)))

(defn merge-users [users]
  (fn [s]
    (update-in s [:data :users] #(merge % users))))

(defn real-user? [user-id]
  (in? (user user-id :permissions) "user"))

(defn admin-user? [user-id]
  (in? (user user-id :permissions) "admin"))

(defn reset-code-info [reset-code]
  (data [:reset-code reset-code]))

(defn set-reset-code-info [reset-code rmap]
  (fn [s]
    (assoc-in s [:data :reset-code reset-code] rmap)))
