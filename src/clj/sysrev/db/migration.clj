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
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-sql-array
              with-debug-sql to-jsonb sql-cast]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project :refer
             [add-project-member set-member-permissions
              default-project-settings]]
            [sysrev.db.groups :as groups]
            [sysrev.article.core :as article]
            [sysrev.db.users :as users]
            [sysrev.label.core :as label]
            [sysrev.label.answer :as answer]
            [sysrev.label.migrate :refer [migrate-all-project-article-resolve]]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.util :refer [parse-xml-str]]
            [sysrev.source.core :as source]
            [sysrev.source.endnote :refer [load-endnote-record]]
            [sysrev.clone-project :as clone]
            [sysrev.pubmed :refer
             [extract-article-location-entries parse-pmid-xml]]
            [sysrev.stripe :as stripe])
  (:import java.util.UUID))

(defn ensure-user-default-project-ids
  "Ensures that a default-project-id value is set for all users which
  belong to at least one project."
  []
  (let [user-ids
        (->>
         (-> (select :u.user-id)
             (from [:web-user :u])
             (where
              [:and
               [:= :u.default-project-id nil]
               [:exists
                (-> (select :*)
                    (from [:project-member :m])
                    (where [:= :m.user-id :u.user-id]))]])
             do-query)
         (map :user-id))]
    (doall
     (for [user-id user-ids]
       (let [project-id
             (-> (select :project-id)
                 (from [:project-member :m])
                 (where [:= :m.user-id user-id])
                 (order-by [:join-date])
                 (limit 1)
                 do-query
                 first
                 :project-id)]
         (-> (sqlh/update :web-user)
             (sset {:default-project-id project-id})
             (where [:= :user-id user-id])
             do-execute))))))

(defmacro create-uuids [table id uuid]
  `(let [ids# (->> (-> (select ~id)
                       (from ~table)
                       (where [:= ~uuid nil])
                       do-query)
                   (map ~id))]
     (->> ids#
          (pmap
           #(-> (sqlh/update ~table)
                (sset {~uuid (UUID/randomUUID)})
                (where [:= ~id %])
                do-execute))
          doall)
     true))

(defn ensure-entry-uuids
  "Creates uuid values for database entries with none set."
  []
  (create-uuids :project :project-id :project-uuid)
  (create-uuids :web-user :user-id :user-uuid)
  (create-uuids :article :article-id :article-uuid))

(defn ensure-permissions-set
  "Sets default permissions values for entries with null value."
  []
  (let [user-defaults (to-sql-array "text" ["user"])
        member-defaults (to-sql-array "text" ["member"])]
    (-> (sqlh/update :web-user)
        (sset {:permissions user-defaults})
        (where [:= :permissions nil])
        do-execute)
    (-> (sqlh/update :project-member)
        (sset {:permissions member-defaults})
        (where [:= :permissions nil])
        do-execute)
    nil))

(defn ensure-article-location-entries []
  (let [articles
        (-> (select :article-id :raw)
            (from [:article :a])
            (where
             [:and
              [:!= :raw nil]
              [:not
               [:exists
                (-> (select :*)
                    (from [:article-location :al])
                    (where [:= :al.article-id :a.article-id]))]]])
            do-query)]
    (->> articles
         (pmap
          (fn [{:keys [article-id raw]}]
            (let [entries
                  (->> (-> raw parse-xml-str :content first
                           extract-article-location-entries)
                       (mapv #(assoc % :article-id article-id)))]
              (when-not (empty? entries)
                (-> (insert-into :article-location)
                    (values entries)
                    do-execute)))))
         doall)
    (when (not= 0 (count articles))
      (log/info
       (str "processed locations for " (count articles) " articles")))))

(defn ensure-no-null-authors []
  (let [invalid (-> (q/select-project-articles nil [:a.article-id :a.authors])
                    (->> do-query
                         (filterv (fn [{:keys [authors]}]
                                    (in? authors nil)))))]
    (doseq [{:keys [article-id authors]} invalid]
      (let [authors (filterv string? authors)]
        (-> (sqlh/update :article)
            (where [:= :article-id article-id])
            (sset {:authors (to-sql-array "text" authors)})
            do-execute)))
    (when-not (empty? invalid)
      (log/info (format "fixed %d invalid authors entries" (count invalid))))))

(defn ensure-project-sources-exist []
  (with-transaction
    (let [project-ids
          (-> (select :project-id)
              (from [:project :p])
              (where
               [:and
                [:not
                 [:exists
                  (-> (select :*)
                      (from [:project-source :ps])
                      (where [:= :ps.project-id :p.project-id]))]]
                [:exists
                 (-> (select :*)
                     (from [:article :a])
                     (where [:= :a.project-id :p.project-id]))]])
              (->> do-query (mapv :project-id)))]
      (if (empty? project-ids)
        (log/info "No projects found with missing source entry")
        (log/info "Creating legacy source entries for"
                  (count project-ids) "projects"))
      (doseq [project-id project-ids]
        (clone/create-project-legacy-source project-id)))))

(defn update-stripe-plans-table
  "Update the stripe_plans table based upon what is stored on stripe. We
  never delete plans, even though they may no longer exist on stripe
  so that there is a record of their existence. If a plan is changed
  on the stripe, it is updated here."
  []
  (let [plans (->> (stripe/get-plans)
                   :data
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
        #(->> % parse-xml-str parse-pmid-xml :date)
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
  (when (= 0 (-> (select :%count.*)
                 (from :user-email)
                 do-query first :count))
    (doseq [{:keys [user-id email]} (-> (select :user-id :email)
                                        (from :web-user)
                                        do-query)]
      (users/create-email-verification! user-id email :principal true))))

(defn ensure-groups
  "Ensure that there are always the required SysRev groups"
  []
  (when-not (groups/group-name->group-id "public-reviewer")
    (groups/create-group! "public-reviewer")))

;; only meant to be used once
(defn set-project-owners []
  (let [projects (->> (-> (select :project_id)
                          (from :project)
                          do-query)
                      (mapv :project-id))]
    (mapv #(if (nil? (project/get-project-owner %))
             (let [project-admin (-> (project/get-project-admins %)
                                     first :user-id)]
               (if-not (nil? project-admin)
                 ;; set the project-admin to owner
                 (api/change-project-owner % :user-id project-admin)
                 ;; set the member with the lowest user-id as owner
                 (let [first-member (-> (project/project-user-ids %) sort first)]
                   (if-not (nil? first-member)
                     ; set the project-admin to first-member
                     (api/change-project-owner % :user-id first-member)
                     ;; this is an orphaned project
                     ;; the following projects are orphaned: 12962 (data extraction rct), 11214 (EBT Course Assignment Copy 2)
                     (println "orphaned project: " %)))))) projects)))

(defn ensure-updated-db
  "Runs everything to update database entries to latest format."
  []
  (doseq [migrate-fn [#'ensure-user-default-project-ids
                      #'ensure-entry-uuids
                      #'ensure-permissions-set
                      ;; #'ensure-article-location-entries
                      ;; #'ensure-no-null-authors
                      #'update-stripe-plans-table
                      #'clone/delete-empty-legacy-sources
                      #'ensure-project-sources-exist
                      #'ensure-user-email-entries
                      #'ensure-groups
                      #'migrate-all-project-article-resolve]]
    (log/info "Running " (str migrate-fn))
    (time ((var-get migrate-fn)))))
