(ns sysrev.file.document
  (:require [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.file.core :as file]
            [sysrev.file.s3 :as s3]))

;; for clj-kondo
(declare lookup-document-file list-project-documents save-document-file)

;;;
;;; "Project Documents" files.
;;;

(s/def ::document map?)

(defn-spec lookup-document-file (s/nilable ::document)
  [project-id int?, file-key string?]
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

(defn-spec save-document-file map?
  [project-id int?, user-id int?, filename string?, file ::s3/file]
  (db/with-clear-project-cache project-id
    (let [{:keys [s3-id]} (file/save-s3-file :document filename {:file file})]
      (q/create :project-document {:s3-id s3-id :project-id project-id :user-id user-id}
                :returning :*))))

;;;
;;; migration
;;;

(defn- create-document-file [{:keys [key filename project-id user-id created delete-time]
                              :as fields}]
  (db/with-clear-project-cache project-id
    (let [s3-id (#'file/create-s3store (-> (select-keys fields [:key :filename :created])
                                           (update :key str)))]
      (q/create :project-document
                (assoc (select-keys fields [:project-id :user-id :delete-time])
                       :s3-id s3-id)))))

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
