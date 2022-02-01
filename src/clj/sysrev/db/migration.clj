(ns sysrev.db.migration
  (:require
   [clojure.tools.logging :as log]
   [sysrev.annotations
    :refer [delete-invalid-annotations migrate-old-annotations]]
   [sysrev.api :as api]
   [sysrev.db.core :as db]
   [sysrev.db.queries :as q]
   [sysrev.group.core :as group]
   [sysrev.label.migrate :refer [migrate-all-project-article-resolve]]
   [sysrev.payment.stripe :as stripe :refer [update-stripe-plans-table]]
   [sysrev.project.core :as project]
   [sysrev.util :as util]))

(defn ensure-groups
  "Ensure that there are always the required SysRev groups"
  []
  (db/with-transaction
    (when-not (group/group-name->id "public-reviewer")
      (group/create-group! "public-reviewer"))))

;; only meant to be used once
(defn set-project-owners []
  (doseq [project-id (q/find :project {} :project-id)]
    (when-not (project/get-project-owner project-id)
      (if-let [project-admin (-> (project/get-project-admins project-id) first :user-id)]
        ;; set the project-admin to owner
        (api/change-project-owner project-id :user-id project-admin)
        ;; set the member with the lowest user-id as owner
        (if-let [first-member (-> (project/project-user-ids project-id) sort first)]
          ;; set the project-admin to first-member
          (api/change-project-owner project-id :user-id first-member)
          ;; this is an orphaned project
          ;; the following projects are orphaned:
          ;;   12962 (data extraction rct)
          ;;   11214 (EBT Course Assignment Copy 2)
          (log/warn "orphaned project: " project-id))))))

(defn ensure-updated-db
  "Runs everything to update database entries to latest format."
  []
  (doseq [migrate-fn [#'update-stripe-plans-table
                      #'ensure-groups
                      #'migrate-all-project-article-resolve
                      #'delete-invalid-annotations
                      #'migrate-old-annotations]]
    (log/info "Running " (str migrate-fn))
    (time (try ((var-get migrate-fn))
               (catch Throwable e
                 (log/error "Exception applying migration %s" (pr-str migrate-fn))
                 (util/log-exception e)
                 (throw e))))))
