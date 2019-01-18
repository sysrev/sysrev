(ns sysrev.db.migration
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.data.json :as json]
            [clojure.data.xml :as dxml]
            [clojure.tools.logging :as log]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction to-sql-array
              with-debug-sql to-jsonb sql-cast]]
            [sysrev.db.project :as project :refer
             [add-project-member set-member-permissions
              default-project-settings]]
            [sysrev.article.core :as article]
            [sysrev.db.users :refer
             [get-user-by-email set-user-permissions generate-api-token create-email-verification!
              current-email-entry]]
            [sysrev.db.labels :refer [add-label-entry-boolean]]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.util :refer [parse-xml-str]]
            [sysrev.source.endnote :refer [load-endnote-record]]
            [sysrev.pubmed :refer
             [extract-article-location-entries parse-pmid-xml]]
            [sysrev.db.queries :as q]
            [sysrev.db.labels :as labels]
            [sysrev.source.core :as source]
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

(defn ensure-label-inclusion-values [& [force?]]
  (let [project-ids
        (-> (select :project-id)
            (from [:project :p])
            (where
             [:or
              (true? force?)
              [:not
               [:exists
                (-> (q/select-project-articles :p.project-id [:*])
                    (q/join-article-labels)
                    (merge-where
                     [:!= :al.inclusion nil]))]]])
            (->> do-query (map :project-id)))]
    (doseq [project-id project-ids]
      (let [alabels
            (-> (q/select-project-articles
                 project-id [:al.*])
                (q/join-article-labels)
                (merge-where [:= :al.inclusion nil])
                do-query)]
        (when (not= 0 (count alabels))
          (log/info (format "updating inclusion fields for %d rows"
                            (count alabels))))
        (doall
         (->>
          alabels
          (pmap
           (fn [alabel]
             (let [inclusion (labels/label-answer-inclusion
                              (:label-id alabel) (:answer alabel))]
               (-> (sqlh/update [:article-label :al])
                   (sset {:inclusion inclusion})
                   (where
                    [:= :al.article-label-id (:article-label-id alabel)])
                   do-execute))))))))))

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
               [:not
                [:exists
                 (-> (select :*)
                     (from [:project-source :ps])
                     (where [:= :ps.project-id :p.project-id]))]])
              (->> do-query (mapv :project-id)))]
      (when (not-empty project-ids)
        (log/info (str "Creating legacy source entries for "
                       (count project-ids) " projects")))
      (doseq [project-id project-ids]
        (let [article-ids (-> (q/select-project-articles
                               project-id [:a.article-id]
                               {:include-disabled? true})
                              (->> do-query (mapv :article-id)))
              source-id (source/create-source
                         project-id
                         (source/make-source-meta :legacy {}))]
          (when (not-empty article-ids)
            (log/info (str "Creating " (count article-ids)
                           " article source entries for project #"
                           project-id)))
          (doseq [article-id article-ids]
            (source/add-article-to-source article-id source-id)))))))

(defn ensure-article-flag-disable-entries []
  (with-transaction
    (let [project-ids
          ;; TODO: update after changes to enabled logic
          (-> (select :project-id)
              (from [:project :p])
              (where
               [:and
                [:exists
                 (-> (select :*)
                     (from [:article :a])
                     (where
                      [:and
                       [:= :a.project-id :p.project-id]
                       [:= :a.enabled false]
                       [:not
                        [:exists
                         (-> (select :*)
                             (from [:article-flag :aflag])
                             (where
                              [:and
                               [:= :aflag.article-id :a.article-id]
                               [:= :aflag.disable true]]))]]
                       #_
                       [:not [:exists disabled article source entry]]]))]])
              (->> do-query (mapv :project-id)))]
      (when (not-empty project-ids)
        (log/info (str "Creating article-flag entries for "
                       (count project-ids) " legacy projects")))
      (doseq [project-id project-ids]
        (let [article-ids
              (-> (select :article-id)
                  (from [:article :a])
                  (where
                   [:and
                    [:= :a.project-id project-id]
                    [:= :a.enabled false]
                    [:not
                     [:exists
                      (-> (select :*)
                          (from [:article-flag :aflag])
                          (where
                           [:and
                            [:= :aflag.article-id :a.article-id]
                            [:= :aflag.disable true]]))]]])
                  (->> do-query (mapv :article-id)))]
          (log/info (str "Creating " (count article-ids)
                         " article-flag disable entries for project #"
                         project-id))
          (doseq [article-id article-ids]
            (article/set-article-flag article-id "legacy-disable" true)))))))

;; TODO: this doesn't work for replacing existing stripe_plan entries
(defn update-stripe-plans-table
  "Update the stripe_plans table based upon what is stored on stripe. We
  never delete plans, even though they may no longer exist on stripe
  so that there is a record of their existence. If a plan is changed
  on the stripe, it is updated here."
  []
  (let [plans (->> (stripe/get-plans)
                   :data
                   (mapv #(select-keys % [:name :amount :product :created :id])))]
    (-> (insert-into :stripe-plan)
        (values plans)
        (upsert (-> (on-conflict :name)
                    (do-update-set :product :amount :id :created)))
        do-execute)))

;; TODO: has this been run? should it be?
(defn update-dates-from-article-raw
  "Extract the date from the raw column of the article and then update
  the corresponding date field"
  []
  (let [pubmed-extract-date
        #(->> % parse-xml-str parse-pmid-xml :date)
        endnote-extract-date
        #(-> % dxml/parse-str load-endnote-record :date)
        article-xml-extract-date
        #(cond (clojure.string/blank? %)
               nil
               (not (clojure.string/blank? (pubmed-extract-date %)))
               (pubmed-extract-date %)
               (not (clojure.string/blank? (endnote-extract-date %)))
               (endnote-extract-date %)
               :else nil)]
    (log/info "Started Converting dates... ")
    (doseq [article (-> (select :raw :article-id)
                        (from [:article :a])
                        (order-by [:a.article-id :desc])
                        do-query)]
      (let [date (article-xml-extract-date (:raw article))]
        (when-not (clojure.string/blank? date)
          (-> (sqlh/update :article)
              (sset {:date date})
              (where [:= :article-id (:article-id article)])
              do-execute))))
    (log/info "Finished Converting Dates. ")))

(defn ensure-web-user-email-entries
  "Migrate to new email verification system, should only be run when the
  web_user_email table is essentially empty"
  []
  (when (< (-> (select :%count.*)
               (from :web-user-email)
               do-query first :count)
           1)
    (let [web-user (-> (select :user-id :email)
                       (from :web-user)
                       do-query)]
      (doall (map #(create-email-verification!
                    (:user-id %)
                    (:email %)
                    :principal true) web-user)))))

(defn ensure-groups
  "Ensure that there are always the required SysRev groups"
  []
  (when-not (= (-> (select :group-name)
                   (from :groups)
                   (where [:= :group-name "public-reviewer"])
                   do-query first :group-name)
               "public-reviewer")
    (-> (insert-into :groups)
        (values [{:group-name "public-reviewer"}])
        do-execute)))

(defn ensure-updated-db
  "Runs everything to update database entries to latest format."
  []
  (doseq [migrate-fn [#'ensure-user-default-project-ids
                      #'ensure-entry-uuids
                      #'ensure-permissions-set
                      ;; #'ensure-article-location-entries
                      ;; #'ensure-label-inclusion-values
                      ;; #'ensure-no-null-authors
                      #'update-stripe-plans-table
                      #'ensure-project-sources-exist
                      #'ensure-web-user-email-entries
                      ;; #'ensure-article-flag-disable-entries
                      #'ensure-groups
                      ]]
    (log/info "Running " (str migrate-fn))
    (time ((var-get migrate-fn)))))
