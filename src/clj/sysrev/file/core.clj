(ns sysrev.file.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.file.s3 :as s3]
            [sysrev.util :as util]))

(s/def ::time any?)
(s/def ::s3-id int?)
(s/def ::key ::s3/file-key)
(s/def ::filename string?)
(s/def ::created ::time)

(s/def ::s3store (s/keys :req-un [::s3-id ::key ::filename ::created]))

(defn-spec ^:private create-s3store ::s3-id
  [{:keys [key filename created] :as fields} (s/keys :req-un [::filename ::key]
                                                     :opt-un [::created])]
  (db/with-transaction
    (if-let [s3-id (q/find-one :s3store {:key key :filename filename} :s3-id)]
      s3-id
      (q/create :s3store fields, :returning :s3-id))))

(defn-spec lookup-s3-id (s/nilable ::s3-id)
  [filename ::filename, key ::key]
  (q/find-one :s3store {:filename filename :key key} :s3-id))

(defn-spec s3-key (s/nilable ::key)
  [s3-id (s/nilable ::s3-id)]
  (when s3-id (q/find-one :s3store {:s3-id s3-id} :key)))

(defn-spec save-s3-file ::s3store
  "Saves a file to S3 and creates s3store entry referencing it,
  returning the s3store entry. Skips uploading file if already exists
  on S3, and skips creating s3store entry if that already exists.
  Multiple s3store entries may exist referencing a single file using
  different filenames."
  [sr-context map?
   bucket ::s3/bucket, filename ::filename,
   {:keys [file file-bytes created]} (s/keys :opt-un [::s3/file ::s3/file-bytes ::created])]
  (util/assert-single file file-bytes)
  (let [file-key (or (some-> file util/file->sha-1-hash)
                     (some-> file-bytes util/byte-array->sha-1-hash))]
    (when-not (s3/s3-key-exists? sr-context bucket file-key)
      (cond file        (s3/save-file sr-context file bucket :file-key file-key)
            file-bytes  (s3/save-byte-array sr-context file-bytes bucket :file-key file-key)))
    (db/with-transaction
      (let [s3-id (create-s3store (cond-> {:key file-key :filename filename}
                                    created (merge {:created created})))]
        (q/find-one :s3store {:s3-id s3-id})))))
