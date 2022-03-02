(ns sysrev.project.plan
  (:require
   [clj-time.coerce :as tc]
   [clj-time.core :as t]
   [clojure.string :as str]
   [medley.core :as medley]
   [sysrev.db.core :as db :refer [with-transaction]]
   [sysrev.db.queries :as q]
   [sysrev.group.core :as group]
   [sysrev.payment.plans :as plans]
   [sysrev.project.core :as project]
   [sysrev.util :as util]))

(def paywall-grandfather-date "2019-06-09 23:56:00")

(defn- project-grandfathered? [project-id]
  (let [{:keys [date-created]} (q/find-one :project {:project-id project-id})]
    (t/before? (tc/from-sql-time date-created)
               (util/parse-time-string paywall-grandfather-date))))

(defn project-owner-plan
  "Return the plan name for the project owner of project-id"
  [project-id]
  (db/with-project-cache project-id [:owner-plan]
    (with-transaction
      (let [{:keys [user-id group-id]} (project/get-project-owner project-id)
            owner-user-id (or user-id (and group-id (group/get-group-owner group-id)))]
        (if owner-user-id
          (let [plan (plans/user-current-plan owner-user-id)]
            (if (or (nil? (:status plan)) ;legacy plan
                    (= (:status plan) "active"))
              (:product-name plan)
              "Basic"))
          "Basic")))))

(defn project-unlimited-access? [project-id]
  (db/with-project-cache project-id [:unlimited-access?]
    (boolean
     (with-transaction
       (or (project-grandfathered? project-id)
           (contains? #{"Premium"} (project-owner-plan project-id)))))))
