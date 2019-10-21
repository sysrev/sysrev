(ns sysrev.db.migration
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.data.json :as json]
            [clojure.data.xml :as dxml]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.api :as api]
            [sysrev.db.core :as db :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project :refer
             [add-project-member set-member-permissions default-project-settings]]
            [sysrev.project.clone :as clone]
            [sysrev.group.core :as group]
            [sysrev.user.core :as user]
            [sysrev.formats.endnote :refer [load-endnote-record]]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.payment.stripe :as stripe]
            [sysrev.label.migrate :refer [migrate-all-project-article-resolve]]
            [sysrev.file.document :refer [migrate-filestore-table]]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [map-values in?]])
  (:import java.util.UUID))

(defn update-stripe-plans-table
  "Update the stripe_plans table based upon what is stored on stripe. We
  never delete plans, even though they may no longer exist on stripe
  so that there is a record of their existence. If a plan is changed
  on the stripe, it is updated here."
  []
  (let [plans (->> (:data (stripe/get-plans))
                   (mapv #(select-keys % [:nickname :created :id]))
                   (mapv #(set/rename-keys % {:nickname :name})))]
    (-> (insert-into :stripe-plan)
        (values plans)
        (upsert (-> (on-conflict :name)
                    (do-update-set :id :created)))
        do-execute)))

;; TODO: has this been run? should it be?
(defn ^:repl update-dates-from-article-raw
  "Extract the date from the raw column of the article and then update
  the corresponding date field"
  []
  (let [pubmed-extract-date
        #(->> % util/parse-xml-str pubmed/parse-pmid-xml :date)
        endnote-extract-date
        #(-> % dxml/parse-str load-endnote-record :date)
        article-xml-extract-date
        #(cond (str/blank? %)
               nil
               (not (str/blank? (pubmed-extract-date %)))
               (pubmed-extract-date %)
               (not (str/blank? (endnote-extract-date %)))
               (endnote-extract-date %)
               :else nil)]
    (log/info "Started Converting dates... ")
    (doseq [article (-> (select :raw :article-id)
                        (from [:article :a])
                        (order-by [:a.article-id :desc])
                        do-query)]
      (let [date (article-xml-extract-date (:raw article))]
        (when-not (str/blank? date)
          (-> (sqlh/update :article)
              (sset {:date date})
              (where [:= :article-id (:article-id article)])
              do-execute))))
    (log/info "Finished Converting Dates. ")))

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
                      #'migrate-filestore-table]]
    (log/info "Running " (str migrate-fn))
    (time ((var-get migrate-fn)))))
