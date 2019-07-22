(ns sysrev.db.documents
  (:require [me.raynes.fs :as fs]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :as db :refer
             [do-query do-execute to-sql-array with-project-cache]]
            [sysrev.shared.util :refer [map-values]]))

(defn load-article-documents
  "Loads and replaces the database entries for article PDF files based on the
  files present in directory `pdfs-path`."
  [project-id pdfs-path]
  (db/with-transaction
    (let [doc-type "article-pdf"
          doc-files (->> (fs/list-dir pdfs-path)
                         (filter fs/directory?)
                         (map (fn [doc-id-path]
                                (let [doc-id (fs/base-name doc-id-path)
                                      files (fs/list-dir doc-id-path)]
                                  [doc-id files])))
                         (apply concat)
                         (apply hash-map))
          db-entries (->> doc-files
                          (map (fn [[doc-id files]]
                                 (->> files
                                      (map #(when (fs/file? %)
                                              (let [fname (fs/base-name %)]
                                                {:project-id project-id
                                                 :document-id doc-id
                                                 :document-type doc-type
                                                 :fs-path fname})))
                                      (remove nil?))))
                          (apply concat))]
      (-> (delete-from :document)
          (where [:and
                  [:= :project-id project-id]
                  [:= :document-type doc-type]])
          do-execute)
      (-> (insert-into :document)
          (values db-entries)
          do-execute)
      (let [dcount (-> (select :%count.*)
                       (from :document)
                       (where [:and
                               [:= :project-id project-id]
                               [:= :document-type doc-type]])
                       do-query first :count)]
        (->> dcount (format "loaded %s article documents") println))
      nil)))

(defn all-article-document-paths [project-id]
  (with-project-cache project-id [:protected :document-paths]
    (-> (select :document-id :fs-path)
        (from :document)
        (where [:and
                [:= :project-id project-id]
                [:= :document-type "article-pdf"]])
        (->> do-query
             (group-by :document-id)
             (map-values #(mapv :fs-path %))))))

(defn load-project-document-ids [project-id article-doc-ids]
  (doseq [article-uuid (keys article-doc-ids)]
    (let [doc-ids (get article-doc-ids article-uuid)]
      (-> (sqlh/update :article)
          (where [:and
                  [:= :project-id project-id]
                  [:or
                   [:= :article-uuid article-uuid]
                   [:= :parent-article-uuid article-uuid]]])
          (sset {:document-ids
                 (to-sql-array "text" doc-ids)})
          do-execute)))
  (let [empty-val (to-sql-array "text" [])
        article-count (-> (select :%count.*)
                          (from :article)
                          (where [:and
                                  [:= :project-id project-id]
                                  [:!= :document-ids nil]
                                  [:!= :document-ids empty-val]])
                          do-query first :count)]
    (println (format "have %s articles with documents attached"
                     article-count)))
  nil)
