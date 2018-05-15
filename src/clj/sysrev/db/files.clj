(ns sysrev.db.files
  (:require [sysrev.db.core :as db :refer
             [do-query do-execute sql-now clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :refer [map-values in?]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defrecord Filerec [project-id file-id name content-md5 etag])

(defn insert-file-rec [rec]
  (-> (insert-into :filestore)
      (values [rec])
      (do-execute))
  (when-let [project-id (:project-id rec)]
    (clear-project-cache project-id)))

(defn list-files-for-project [project-id]
  (->>
   (-> (select :*)
       (from :filestore)
       (where [:and
               [:= nil :delete-time]
               [:= :project-id project-id]])
       (order-by [[:ordering (sql/raw "NULLS FIRST")] [:upload-time]])
       do-query)
   (mapv map->Filerec)))

(defn file-by-id [id project-id]
  (->>
   (-> (select :*)
       (from :filestore)
       (where [:and
               [:= :file-id id]
               ;; Require file owned by given project.
               [:= :project-id project-id]])
       (limit 1)
       (do-query)
       (first))
   map->Filerec))

(defn mark-deleted [id project-id]
  (-> (sqlh/update :filestore)
      (sset {:delete-time (sql-now)})
      (where [:and
              [:= :file-id id]
              [:= :project-id project-id]])
      (do-execute))
  (when project-id
    (clear-project-cache project-id)))

;; CREATE TABLE s3store (id serial primary key,filename text, key text, created timestamp with time zone not null default now(),unique(key,filename));
(defn insert-file-hash-s3-record
  "Given a filename and key, insert a record for it in the db"
  [filename key]
  (-> (insert-into :s3store)
      (values [{:filename filename
                :key key}])
      (do-execute)))

(defn s3-has-key?
  "Does the s3store have a file with key?"
  [key]
  (->
   ;;the hash of a file is used as it's key
   (select [:key])
   (from :s3store)
   (where [:= :key key])
   do-query
   first
   nil?
   not))

(defn id-for-s3-filename-key-pair
  "Given a filename and key, return the id of the relation "
  [filename key]
  (->
   (select :id)
   (from :s3store)
   (where [:and
           [:= :filename filename]
           [:= :key key]])
   do-query
   first
   :id))

;; CREATE TABLE article_pdf (s3_id integer not null references s3store (id) on delete cascade, article_id integer not null references article (article_id) on delete cascade, unique (s3_id, article_id));
(defn associate-s3-with-article
  "Associate a file/key pair with an article"
  [s3-id article-id]
  (-> (insert-into :article-pdf)
      (values [{:article-id article-id
                :s3-id s3-id}])
      (do-execute)))

(defn get-article-s3-association
  "Given an s3-id and article-id, return the association"
  [s3-id article-id]
  (-> (select :s3_id)
      (from :article_pdf)
      (where [:and
              [:= :s3-id s3-id]
              [:= :article-id article-id]])
      do-query
      first
      :s3-id))

(defn get-article-file-maps
  "Given an article-id, return a coll of file maps that correspond to that article"
  [article-id]
  (-> (select :filename :key)
      (from :s3store)
      (where [:in :s3store.id (-> (select :s3_id)
                                  (from :article_pdf)
                                  (where [:= :article_id article-id]))])
      do-query))
