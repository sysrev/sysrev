(ns sysrev.db.transfer
  (:require
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [clojure.java.jdbc :as j]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction make-db-config]]
   [sysrev.db.queries :as q]
   [sysrev.db.articles :refer [article-to-sql]]
   [sysrev.shared.util :refer [map-values in?]]
   [clojure.pprint :as pprint]))

(defn transfer-project
  "Transfers all database entries for a project from a source Postgres server
  to a destination Postgres server."
  [project-uuid src-postgres-overrides dest-postgres-overrides]
  (let [src-db (make-db-config src-postgres-overrides)
        dest-db (make-db-config dest-postgres-overrides)]
    (assert (nil?
             (q/query-project-by-uuid project-uuid [:*] dest-db))
            "project already exists on destination server")
    (assert ((comp not nil?)
             (q/query-project-by-uuid project-uuid [:*] src-db))
            "project does not exist on source server")
    (let [{src-project-id :project-id
           project-name :name
           :as src-project} (q/query-project-by-uuid project-uuid [:*] src-db)
          _ (println (format "transferring project data for '%s'" project-name))
          [src-labels src-articles src-locations]
          [(-> (select :*)
               (from :label)
               (where [:= :project-id src-project-id])
               (do-query src-db))
           (-> (select :*)
               (from :article)
               (where [:= :project-id src-project-id])
               (do-query src-db))
           (-> (select :al.*)
               (from [:article-location :al])
               (join [:article :a]
                     [:= :a.article-id :al.article-id])
               (where [:= :a.project-id src-project-id])
               (do-query src-db))]]
      (assert ((comp not empty?) src-articles)
              "source project has no articles")
      (do-transaction
       dest-db
       (let [dest-project-id
             (-> (insert-into :project)
                 (values
                  [(-> src-project
                       (dissoc :project-id))])
                 (returning :project-id)
                 do-query first :project-id)
             _ (println "created project entry")
             dest-labels
             (-> (insert-into :label)
                 (values
                  (->>
                   src-labels
                   (mapv
                    #(-> %
                         (dissoc :label-id-local)
                         (assoc :project-id dest-project-id)))))
                 (returning :*)
                 do-query)
             _ (println "created label entries")
             dest-article-ids
             (zipmap
              (mapv :article-id src-articles)
              (->>
               src-articles
               (partition-all 25)
               vec
               (mapv
                (fn [agroup]
                  (j/with-db-connection [db-conn dest-db]
                    (->>
                     (-> (insert-into :article)
                         (values
                          (->>
                           agroup
                           (mapv
                            #(-> %
                                 (article-to-sql db-conn)
                                 (dissoc :article-id)
                                 (assoc :project-id dest-project-id)))))
                         (returning :article-id)
                         do-query)
                     (mapv :article-id)))))
               (apply concat)))
             _ (println "created article entries")
             dest-locations
             (->>
              src-locations
              (partition-all 50)
              vec
              (mapv
               (fn [lgroup]
                 (->>
                  (-> (insert-into :article-location)
                      (values
                       (->>
                        lgroup
                        (mapv
                         (fn [sl]
                           (-> sl
                               (dissoc :location-id)
                               (update :article-id
                                       #(get dest-article-ids %)))))))
                      (returning :location-id)
                      do-query)
                  (mapv :location-id))))
              (apply concat))
             _ (println "created article-location entries")]
         (println)
         ;;(println (str "dest-article-ids = " (pr-str dest-article-ids)))
         (println "----------------------------------")
         (println
          (format "labels : [%d, %d]"
                  (count src-labels) (count dest-labels)))
         (println
          (format "articles : [%d, %d]"
                  (count src-articles) (count dest-article-ids)))
         (println
          (format "locations : [%d, %d]"
                  (count src-locations) (count dest-locations)))
         (println "----------------------------------")
         (println "done"))))))
