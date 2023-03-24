(ns sysrev.file.document
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [sysrev.db.core :as db]
   [sysrev.db.queries :as q]
   [sysrev.file.core :as file]
   [sysrev.file.s3 :as s3]))

;;;
;;; "Project Documents" files.
;;;

(s/def ::document map?)

(defn-spec lookup-document-file (s/nilable ::document)
  [project-id int?, s3-id int?]
  (q/find-one [:project-document :pd] {:pd.project-id project-id :pd.s3-id s3-id :delete-time nil}
              [:pd.* :s3.key :s3.filename :s3.created]
              :join [[:s3store :s3] :pd.s3-id]))

(defn-spec list-project-documents (s/nilable (s/coll-of ::document))
  [project-id int?]
  (q/find [:project-document :pd] {:project-id project-id :delete-time nil}
          [:pd.* :s3.key :s3.filename :s3.created]
          :join [[:s3store :s3] :pd.s3-id]
          :order-by :s3.created))

(defn-spec mark-document-file-deleted ::document
  "Sets `delete-time` to make the file invisible to users while keeping
  the entries in database and S3."
  [project-id int?, s3-id int?]
  (db/with-clear-project-cache project-id
    (when-let [{:keys [pdoc-id]} (lookup-document-file project-id s3-id)]
      (q/modify :project-document {:pdoc-id pdoc-id} {:delete-time :%now}))))

(defn-spec save-document-file map?
  [sr-context map? project-id int?, user-id int?, filename string?, file ::s3/file]
  (db/with-clear-project-cache project-id
    (let [{:keys [s3-id]} (file/save-s3-file sr-context :document filename {:file file})
          previously-existed? (q/find-one [:project-document :pd] {:pd.project-id project-id :s3-id s3-id})]
      (if previously-existed?
        (q/modify :project-document {:s3-id s3-id :project-id project-id}
                  {:delete-time nil})
        (q/create :project-document {:s3-id s3-id :project-id project-id :user-id user-id}
                  :returning :*)))))
