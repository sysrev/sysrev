(ns sysrev.db.migration
  (:require [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [insert-into values]]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [sysrev.api :as api]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.group.core :as group]
            [sysrev.user.core :as user]
            [sysrev.payment.stripe :as stripe]
            [sysrev.label.migrate :refer [migrate-all-project-article-resolve]]
            [sysrev.file.document :refer [migrate-filestore-table]]
            [sysrev.annotations :refer [migrate-old-annotations]]
            [sysrev.util :as util]))

(defn update-stripe-plans-table
  "Update the stripe_plans table based upon what is stored on stripe. We
  never delete plans, even though they may no longer exist on stripe
  so that there is a record of their existence. If a plan is changed
  on the stripe, it is updated here."
  []
  (let [plans (->> (:data (stripe/get-plans))
                   (mapv #(select-keys % [:nickname :created :id :interval :amount :tiers]))
                   (mapv #(update % :created (partial util/to-clj-time)))
                   (mapv #(update % :tiers db/to-jsonb)))]
    (when-let [invalid-plans (seq (->> plans (filter #(nil? (:nickname %)))))]
      (log/warnf "invalid stripe plan entries:\n%s" (pr-str invalid-plans)))
    (let [valid-plans (->> plans (remove #(nil? (:nickname %))))]
      (-> (insert-into :stripe-plan)
          (values valid-plans)
          (upsert (-> (on-conflict :nickname)
                      (do-update-set :id :created :interval :amount :tiers)))
          db/do-execute))))

(defn ensure-user-email-entries
  "Migrate to new email verification system, should only be run when the
  user-email table is essentially empty"
  []
  (when (zero? (q/find-count :user-email {}))
    (doseq [{:keys [user-id email]} (q/find :web-user {})]
      (user/create-email-verification! user-id email :principal true))))

(defn ensure-groups
  "Ensure that there are always the required SysRev groups"
  []
  (when-not (group/group-name->id "public-reviewer")
    (group/create-group! "public-reviewer")))

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
                      #'ensure-user-email-entries
                      #'ensure-groups
                      #'migrate-all-project-article-resolve
                      #'migrate-filestore-table
                      #'migrate-old-annotations]]
    (log/info "Running " (str migrate-fn))
    (time (try ((var-get migrate-fn))
               (catch Throwable e
                 (log/error "Exception applying migration %s" (pr-str migrate-fn))
                 (util/log-exception e)
                 (throw e))))))
