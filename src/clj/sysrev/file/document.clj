(ns sysrev.file.document
  (:require [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.file.core :as file]
            [sysrev.file.s3 :as s3]
            [sysrev.util :as util]))

;;;
;;; "Project Documents" files.
;;;

(s/def ::document map?)
(s/def ::key string?)
(s/def ::filename string?)

(defn-spec lookup-document-file (s/nilable ::document)
  [project-id int?, file-key ::key]
  (q/find-one [:project-document :pd] {:pd.project-id project-id :s3.key file-key}
              [:pd.* :s3.key :s3.filename :s3.created]
              :join [:s3store:s3 :pd.s3-id]))

(defn-spec list-project-documents (s/nilable (s/coll-of ::document))
  [project-id int?]
  (q/find [:project-document :pd] {:project-id project-id :delete-time nil}
          [:pd.* :s3.key :s3.filename :s3.created]
          :join [:s3store:s3 :pd.s3-id]
          :order-by :s3.created))

(defn mark-document-file-deleted
  "Sets `delete-time` to make the file invisible to users while keeping
  the entries in database and S3."
  [project-id file-key]
  (db/with-clear-project-cache project-id
    (when-let [{:keys [pdoc-id]} (lookup-document-file project-id file-key)]
      (q/modify :project-document {:pdoc-id pdoc-id} {:delete-time :%now}))))

(defn- create-document-file
  "Creates database entries corresponding to an S3 file."
  [{:keys [key filename project-id user-id created delete-time]
    :as fields}]
  (db/with-clear-project-cache project-id
    (let [s3-id (file/create-s3store (-> (select-keys fields [:key :filename :created])
                                         (update :key str)))]
      (q/create :project-document (assoc (select-keys fields [:project-id :user-id :delete-time])
                                         :s3-id s3-id)
                :returning :*))))

(defn-spec save-document-file map?
  "Creates database entries and saves file to S3."
  [project-id int?, user-id int?, filename string?, file any?]
  (db/with-clear-project-cache project-id
    (let [file-key (util/random-uuid)
          entry (create-document-file {:key file-key :filename filename
                                       :project-id project-id :user-id user-id})]
      (s3/save-file file :document :file-key file-key)
      entry)))

(defn migrate-filestore-table-needed? []
  (and (q/table-exists? :project-document)
       (zero? (q/table-count :project-document))
       (try (pos? (q/table-count :filestore))
            (catch Throwable _ false))))

(defn migrate-filestore-table []
  (when (migrate-filestore-table-needed?)
    (log/info "creating entries in project-document")
    (db/with-transaction
      (doseq [{:keys [project-id user-id file-id name upload-time delete-time]}
              (q/find :filestore {} :*)]
        (create-document-file {:key file-id :filename name
                               :project-id project-id :user-id user-id
                               :created upload-time :delete-time delete-time})))))
