(ns sysrev.import.zip
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [sysrev.db.core :as db :refer [with-transaction]]
            [sysrev.db.articles :as articles]
            [sysrev.db.files :as files]
            [sysrev.files.s3store :as s3store]
            [sysrev.db.sources :as sources]
            [sysrev.util :as util :refer [shell]]
            [clojure.string :as str])
  (:import [java.util.zip ZipFile ZipInputStream]))

(defn file->zip-file
  [^java.io.File file]
  (ZipFile. file))

;;; https://stackoverflow.com/questions/5419125/reading-a-zip-file-using-java-api-from-clojure
(defn pdf-zip-entries
  [^java.util.zip.ZipFile zip-file]
  ;; ZipFile seems to be automatically be closed after
  ;; .entries method invocation
  (->> (enumeration-seq (.entries zip-file))
       ;; filter out filenames that don't have
       ;; a pdf extension
       (filter #(some-> % (.getName) (fs/extension)
                        (str/lower-case) (= ".pdf")))
       ;; there seems to be spurious entries,
       ;; filtering based on size
       (filter #(> (.getSize %) 256))))

(defn save-article-pdf
  "Given a zip-file-map, handle saving a file on S3, the associated
  accounting with it and creating an article association with it."
  [{:keys [article-id filename byte-array]}]
  (with-transaction
    (let [hash (util/byte-array->sha-1-hash byte-array)
          s3-id (files/id-for-s3-filename-key-pair
                 filename hash)
          article-s3-association (files/get-article-s3-association
                                  s3-id
                                  article-id)]
      (cond
        ;; there is a file and it is already associated with this article
        (not (nil? article-s3-association))
        {:result {:success true
                  :key hash
                  :filename filename}}
        ;; there is a file, but it is not associated with this article
        (not (nil? s3-id))
        (try (do (files/associate-s3-with-article s3-id
                                                  article-id)
                 {:result {:success true
                           :key hash
                           :filename filename}})
             (catch Throwable e
               {:result
                {:success false
                 :message (.getMessage e)
                 :filename filename}}))
        ;; there is a file. but not with this filename
        (and (nil? s3-id) (files/s3-has-key? hash))
        (try
          (let [ ;; create the association between this file name and
                ;; the hash
                _ (files/insert-file-hash-s3-record filename hash)
                ;; get the new association's id
                s3-id (files/id-for-s3-filename-key-pair filename hash)]
            (files/associate-s3-with-article s3-id
                                             article-id)
            {:result {:success true
                      :key hash
                      :filename filename}})
          (catch Throwable e
            {:result {:success false
                      :message (.getMessage e)
                      :filename filename}}))
        ;; the file does not exist in our s3 store
        (and (nil? s3-id) (not (files/s3-has-key? hash)))
        (try
          (let [ ;; create a new file on the s3 store
                _ (s3store/save-byte-array byte-array)
                ;; create a new association between this file name
                ;; and the hash
                _ (files/insert-file-hash-s3-record filename hash)
                ;; get the new association's id
                s3-id (files/id-for-s3-filename-key-pair filename hash)]
            (files/associate-s3-with-article s3-id article-id)
            {:result {:success true, :key hash, :filename filename}})
          (catch Throwable e
            {:result {:success false, :message (.getMessage e), :filename filename}}))
        :else {:result {:success false
                        :message "Unknown Processing Error Occurred."
                        :filename filename}}))))

(defn- add-article [article project-id source-id]
  (try
    (let [article-id (articles/add-article article project-id)]
      (sources/add-article-to-source article-id source-id)
      article-id)
    (catch Throwable e
      (throw (Exception.
              (str "exception in sysrev.import.endnote/add-article "
                   "article:" (:primary-title article) " "
                   "message: " (.getMessage e))))
      nil)))

(defn import-pdfs-from-zip-file!
  [file filename project-id & {:keys []}]
  (let [meta (sources/make-source-meta :pdf-zip {:filename filename})
        source-id (sources/create-source
                   project-id (assoc meta :importing-articles? true))]
    (future
      (try
        (let [zip-file (file->zip-file file)
              pdfs (pdf-zip-entries zip-file)
              save-pdf (fn [zip-entry]
                         (let [pdf-filename (fs/base-name (.getName zip-entry))
                               pdf-data (-> (.getInputStream zip-file zip-entry)
                                            (util/slurp-bytes))]
                           (save-article-pdf
                            {:article-id
                             (add-article {:primary-title pdf-filename}
                                          project-id source-id)
                             :filename pdf-filename
                             :byte-array pdf-data})))
              success?
              (try
                (if (empty? pdfs)
                  false
                  (do (doall (pmap save-pdf pdfs))
                      true))
                (catch Throwable e
                  (log/error "sysrev.import.zip/import-pdfs-from-zip-file!:"
                             (.getMessage e))
                  false))]
          (with-transaction
            (if success?
              (sources/update-source-meta
               source-id (assoc meta :importing-articles? false))
              (sources/fail-source-import source-id))
            (sources/update-project-articles-enabled project-id))
          success?)
        (catch Throwable e
          (log/error "import-pdfs-from-zip-file! exception:"
                     (.getMessage e))
          (.printStackTrace e)
          (with-transaction
            (sources/fail-source-import source-id)
            (sources/update-project-articles-enabled project-id))
          false)))
    true))
