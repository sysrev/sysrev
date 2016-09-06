(ns sysrev-web.routes
  (:require
   [sysrev-web.base :refer [state server-data on-page?]]
   [sysrev-web.ajax :refer [pull-initial-data pull-project-info pull-all-labels]]
   [sysrev-web.util :refer [nav]]
   [secretary.core :include-macros true :refer-macros [defroute]]))

;; This var records the elements of `server-data` that are required by
;; a page on the web site, so that rendering can be delayed until
;; all the required data has been received.
(def page-data-fields
  {:ranking
   [[:criteria] [:articles] [:labels] [:sysrev] [:ranking]]
   :users
   [[:criteria] [:articles] [:sysrev]]
   :project
   [[:criteria] [:articles] [:sysrev]]
   :classify
   [[:criteria] [:articles] [:labels] [:sysrev]]
   :user-profile
   [[:criteria] [:articles] [:labels] [:sysrev]]
   :labels
   [[:criteria]]})

(def public-pages
  [:home :login :register :project :users :labels :user-profile])

(defn on-public-page? []
  (some on-page? public-pages))

(defn data-initialized?
  "Test whether all server data required for a page has been received."
  [page]
  (let [required-fields (get page-data-fields page)]
    (every? #(not= :not-found (get-in @server-data % :not-found))
            required-fields)))

(defn set-page-state [s]
  (swap! state assoc :page s))

(defroute home "/" []
  (nav "/project"))

(defroute ranking "/ranking" []
  (set-page-state {:ranking
                   {:ranking-page 0
                    :filters {}}})
  (pull-initial-data))

(defroute login "/login" []
  (set-page-state {:login {:email "" :password "" :submit false}}))

(defroute register "/register" []
  (set-page-state {:register {:email "" :password "" :submit false}}))

(defroute users "/users" []
  (set-page-state {:users {}})
  (pull-initial-data)
  ;; Re-fetch project info in case it has changed
  (when (:sysrev @server-data)
    (pull-project-info)))

(defroute project "/project" []
  (set-page-state {:project {}})
  (pull-initial-data)
  ;; Re-fetch project info in case it has changed
  (when (:sysrev @server-data)
    (pull-project-info)))

(defroute self-profile "/user" []
  (set-page-state {:user-profile
                   {:self true
                    :user-id (-> @state :identity :id)}})
  (pull-initial-data)
  ;; Re-fetch user label data in case it has changed
  (when (:labels @server-data)
    (pull-all-labels))
  ;; Re-fetch project info in case it has changed
  (when (:sysrev @server-data)
    (pull-project-info)))

(defroute user-profile "/user/:id" [id]
  (let [id (js/parseInt id)]
    (set-page-state {:user-profile
                     {:self false
                      :user-id id}})
    (pull-initial-data)
    ;; Re-fetch user label data in case it has changed
    (when (:labels @server-data)
      (pull-all-labels))
    ;; Re-fetch project info in case it has changed
    (when (:sysrev @server-data)
      (pull-project-info))))

(defroute classify "/classify" []
  (set-page-state {:classify {:label-values {}}})
  (pull-initial-data))

(defroute labels "/labels" []
  (set-page-state {:labels {}})
  (pull-initial-data))
