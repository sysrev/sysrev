(ns sysrev.db.files
  (:require [clj-http.client :as http]
            [gravatar.core :as gr]
            [sysrev.db.core :as db :refer
             [do-query do-execute sql-now clear-project-cache to-jsonb]]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.util :as u]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

;;;
;;; Old system for "Project Documents" files.
;;; TODO: Merge this into new s3store system.
;;;

(defrecord Filerec [project-id file-id name content-md5 etag])

(defn insert-document-file-rec [rec]
  (-> (insert-into :filestore)
      (values [rec])
      (do-execute))
  (when-let [project-id (:project-id rec)]
    (clear-project-cache project-id)))

(defn list-document-files-for-project [project-id]
  (-> (select :*)
      (from :filestore)
      (where [:and
              [:= nil :delete-time]
              [:= :project-id project-id]])
      (order-by [[:ordering (sql/raw "NULLS FIRST")] [:upload-time]])
      (->> do-query (mapv map->Filerec))))

(defn document-file-by-id [id project-id]
  (-> (select :*)
      (from :filestore)
      (where [:and
              [:= :file-id id]
              ;; Require file owned by given project.
              [:= :project-id project-id]])
      (limit 1)
      do-query first map->Filerec))

(defn mark-document-file-deleted [id project-id]
  (assert (integer? project-id))
  (-> (sqlh/update :filestore)
      (sset {:delete-time (sql-now)})
      (where [:and
              [:= :file-id id]
              [:= :project-id project-id]])
      (do-execute))
  (clear-project-cache project-id))

;;;
;;; New s3 file system (used for PDFs associated with articles)
;;;

(defn insert-file-hash-s3-record
  "Given a filename and key, insert a record for it in the db"
  [filename key]
  (-> (insert-into :s3store)
      (values [{:filename filename, :key key}])
      (do-execute)))

(defn s3-has-key?
  "Does the s3store have a file with key?"
  [key]
  (boolean (-> (select :key)
               (from :s3store)
               (where [:= :key key])
               do-query first :key)))

(defn s3-id-from-filename-key
  "Returns s3-id associated with pair of filename and key."
  [filename key]
  (-> (select :id)
      (from :s3store)
      (where [:and
              [:= :filename filename]
              [:= :key key]])
      do-query first :id))

(defn s3-id-from-article-key
  "Returns s3-id associated with pair of article-id and s3store key."
  [article-id key]
  (-> (select :s3.id)
      (from [:s3store :s3])
      (join [:article-pdf :apdf] [:= :apdf.s3-id :s3.id])
      (where [:and
              [:= :s3.key key]
              [:= :apdf.article-id article-id]])
      do-query first :id))

(defn associate-s3-file-with-article
  "Associate an s3 filename/key pair with an article."
  [s3-id article-id]
  (-> (insert-into :article-pdf)
      (values [{:article-id article-id, :s3-id s3-id}])
      (do-execute)))

(defn dissociate-s3-file-from-article
  "Remove any article-pdf association between s3-id and article-id."
  [s3-id article-id]
  (-> (delete-from :article-pdf)
      (where [:and
              [:= :article-id article-id]
              [:= :s3-id s3-id]])
      do-execute))

(defn s3-article-association?
  "Returns true if article-pdf association exists between s3-id and article-id, false if not."
  [s3-id article-id]
  (boolean (and s3-id article-id
                (-> (select :s3-id)
                    (from :article-pdf)
                    (where [:and
                            [:= :s3-id s3-id]
                            [:= :article-id article-id]])
                    do-query first :s3-id))))

(defn get-article-file-maps
  "Returns a coll of s3store file maps associated with article-id."
  [article-id]
  (-> (select :filename :key :id)
      (from :s3store)
      (where [:in :s3store.id (-> (select :s3-id)
                                  (from :article-pdf)
                                  (where [:= :article-id article-id]))])
      do-query))

(defn s3-id->key
  "Returns s3store key value from entry identified by s3-id."
  [s3-id]
  (-> (select :key)
      (from :s3store)
      (where [:= :id s3-id])
      do-query first :key))

(defn get-profile-image-s3-association
  "Given an s3-id and user-id, return the association"
  [s3-id user-id]
  (-> (select :s3-id)
      (from :web-user-profile-image)
      (where [:and
              [:= :s3-id s3-id]
              [:= :user-id user-id]])
      do-query first :s3-id))

(defn associate-profile-image-s3-with-user
  "Associate a s3store id with a user's profile image"
  [s3-id user-id]
  (-> (insert-into :web-user-profile-image)
      (values [{:user-id user-id
                :s3-id s3-id
                :active true}])
      do-execute))

(defn deactivate-all-profile-images
  "Deactivate all user profile images for user-id"
  [user-id]
  (-> (sqlh/update :web-user-profile-image)
      (sset {:active false})
      (where [:= :user-id user-id])
      do-execute))

(defn activate-profile-image
  "Activate s3-id for user-id, deactivate all others"
  [s3-id user-id]
  (deactivate-all-profile-images user-id)
  (-> (sqlh/update :web-user-profile-image)
      (sset {:active true})
      (where [:and
              [:= :user-id user-id]
              [:= :s3-id s3-id]])
      do-execute))

(defn active-profile-image-key-filename
  "Return the key, filename associated with the active profile"
  [user-id]
  (let [s3-id (-> (select :s3-id)
                  (from :web-user-profile-image)
                  (where [:and
                          [:= :user-id user-id]
                          [:= :active true]])
                  do-query first :s3-id)]
    (-> (select :key :filename :id)
        (from :s3store)
        (where [:= :id s3-id])
        do-query first)))

(defn active-profile-image-meta
  "Return image profile meta for user-id"
  [user-id]
  (-> (select :meta)
      (from :web_user_profile_image)
      (where [:and
              [:= :active true]
              [:= :user_id user-id]])
      do-query first :meta))

(defn update-profile-image-meta!
  [s3-id meta]
  (-> (sqlh/update :web_user_profile_image)
      (sset {:meta (to-jsonb meta)})
      (where [:= :s3-id s3-id])
      do-execute))

(defn read-avatar
  [user-id]
  (-> (select :*)
      (from :web_user_avatar_image)
      (where [:= :user-id user-id])
      do-query first))

(defn associate-avatar-image-with-user
  "Associate a s3store id with user's avatar image"
  [s3-id user-id]
  (-> (insert-into :web_user_avatar_image)
      (values [{:s3-id s3-id
                :user-id user-id}])
      do-execute))

(defn avatar-image-key-filename
  "Return the key, filename associated with the active profile"
  [user-id]
  (let [s3-id (-> (select :s3-id)
                  (from :web-user-avatar-image)
                  (where [:and
                          [:= :user-id user-id]
                          [:= :active true]])
                  do-query first :s3-id)]
    (-> (select :key :filename :id)
        (from :s3store)
        (where [:= :id s3-id])
        do-query first)))

(defn gravatar-link
  [email]
  (let [gravatar-link (gr/avatar-url email :default 404 :https true)
        response (http/get gravatar-link
                           {:throw-exceptions false
                            :as :byte-array
                            ;; needed to prevent 403 (permission denied)
                            :headers {"User-Agent"
                                      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/33.0.1750.152 Safari/537.36"}})]
    (if-not (= (:status response) 404)
      (:body response))))

(defn delete-file!
  [s3-id]
  (-> (delete-from :s3store)
      (where [:= :id s3-id])
      do-execute))
