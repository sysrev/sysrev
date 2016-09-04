(ns sysrev-web.routes
  (:require
   [sysrev-web.base :refer [state server-data]]
   [sysrev-web.ajax :refer [pull-initial-data pull-project-users pull-all-labels]]
   [secretary.core :include-macros true :refer-macros [defroute]]))

;; This var records the elements of `server-data` that are required by
;; a page on the web site, so that rendering can be delayed until
;; all the required data has been received.
(def page-data-fields
  {:ranking
   [[:criteria] [:articles] [:labels] [:users] [:ranking]]
   :users
   [[:criteria] [:articles] [:users]]
   :classify
   [[:criteria] [:articles] [:labels] [:users]]
   :user-profile
   [[:criteria] [:articles] [:labels] [:users]]
   :labels
   [[:criteria]]})

(defn data-initialized?
  "Test whether all server data required for a page has been received."
  [page]
  (let [required-fields (get page-data-fields page)]
    (every? #(not= :not-found (get-in @server-data % :not-found))
            required-fields)))

(defn set-page-state [s]
  (swap! state assoc :page s))

(defroute home "/" []
  (set-page-state {:ranking
                   {:ranking-page 0
                    :filters {}}})
  (pull-initial-data))

(defroute login "/login" []
  (set-page-state {:login {}}))

(defroute register "/register" []
  (set-page-state {:register {}}))

(defroute users "/users" []
  (set-page-state {:users {}})
  (pull-initial-data)
  ;; Re-fetch :users data in case it has changed
  (when (:users @server-data)
    (pull-project-users)))

(defroute self-profile "/user" []
  (set-page-state {:user-profile
                   {:self true
                    :user-id (-> @state :identity :id)}})
  (pull-initial-data)
  ;; Re-fetch user label data in case it has changed
  (when (:labels @server-data)
    (pull-all-labels)))

(defroute user-profile "/user/:id" [id]
  (let [id (js/parseInt id)]
    (set-page-state {:user-profile
                     {:self false
                      :user-id id}})
    (pull-initial-data)
    ;; Re-fetch user label data in case it has changed
    (when (:labels @server-data)
      (pull-all-labels))))

(defroute classify "/classify" []
  (set-page-state {:classify {:label-values {}}})
  (pull-initial-data))

(defroute labels "/labels" []
  (set-page-state {:labels {}})
  (pull-initial-data))
