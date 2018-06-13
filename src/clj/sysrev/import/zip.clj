(ns sysrev.import.zip
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [sysrev.db.articles :as articles]
            [sysrev.db.files :as files]
            [sysrev.files.s3store :as s3store]
            [sysrev.db.sources :as sources]
            [sysrev.util :as util])
  (:import [java.util.zip ZipFile ZipInputStream]))


(defn file->zip-file
  [^java.io.File file]
  (ZipFile. file))

(defn zip-file-map
  "Given a zip-file and zip-entries, return the {:name <file> :byte-array byte[]} for
  each entry."
  ([^java.util.zip.ZipFile zip-file zip-file-entries]
   (zip-file-map zip-file zip-file-entries []))
  ([^java.util.zip.ZipFile zip-file zip-file-entries result]
   (let [zip-entry (first zip-file-entries)]
     (if (empty? zip-file-entries)
       result
       (zip-file-map zip-file (rest zip-file-entries)
                     (conj result
                           (hash-map :filename (fs/base-name (.getName zip-entry))
                                     :byte-array (util/slurp-bytes (.getInputStream zip-file zip-entry)))))))))

;;https://stackoverflow.com/questions/5419125/reading-a-zip-file-using-java-api-from-clojure
(defn pdf-zip-entries
  [^java.util.zip.ZipFile zip-file]
  ;; ZipFile seems to be automatically be closed after
  ;; .entries method invocation
  (->> (enumeration-seq (.entries zip-file))
       ;; filter out filenames that don't have
       ;; a pdf extension
       (filter #(= (fs/extension (.getName %))
                   ".pdf"))
       ;; there seems to be spurious entries,
       ;; filtering based on size
       (filter #(> (.getSize %) 256))))

(defn pdf-zip-file->zip-file-maps
  "Given a zip file with pdfs, extract only the pdfs into a zip-file-map"
  [^java.io.File file]
  (let [pdf-zip-file (file->zip-file file)]
    (zip-file-map pdf-zip-file
                  (pdf-zip-entries pdf-zip-file))))
;;(map #(util/byte-array->sha-1-hash (:byte-array %)) (pdf-zip-file->zip-file-map  (java.io.File. "pdfs.zip")))
(defn save-article-pdf
  "Given a zip-file-map, handle saving a file on S3, the associated accounting with it and creating an article association with it. "
  [{:keys [article-id filename byte-array]}]
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
      (and (nil? s3-id)
           (files/s3-has-key? hash))
      (try
        (let [;; create the association between this file name and
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
      (and (nil? s3-id)
           (not (files/s3-has-key? hash)))
      (try
        (let [ ;; create a new file on the s3 store
              _ (s3store/save-byte-array byte-array)
              ;; create a new association between this file name
              ;; and the hash
              _ (files/insert-file-hash-s3-record filename hash)
              ;; get the new association's id
              s3-id (files/id-for-s3-filename-key-pair filename hash)]
          (files/associate-s3-with-article s3-id article-id)
          {:result {:success true
                    :key hash
                    :filename filename}})
        (catch Throwable e
          {:result {:success false
                    :message (.getMessage e)
                    :filename filename}}))
      :else {:result {:success false
                      :message "Unknown Processing Error Occurred."
                      :filename filename}})))

(defn- add-article [article project-id source-id]
  (try
    (let [article-id (articles/add-article article project-id)]
      (sources/add-article-to-source! article-id source-id)
      article-id)
    (catch Throwable e
      (throw (Exception.
              (str "exception in sysrev.import.endnote/add-article "
                   "article:" (:primary-title article) " "
                   "message: " (.getMessage e))))
      nil)))

;; note: this is not currently parallelized
(defn import-pdfs-from-zip-file! [file filename project-id & {:keys [use-future? threads]
                                                              :or {use-future? false threads 1}}]
  (let [meta (sources/import-articles-from-zip-file-meta filename )
        source-id (sources/create-project-source-metadata!
                   project-id
                   (assoc meta
                          :importing-articles? true))
        zip-file-maps (pdf-zip-file->zip-file-maps file)
        ;; create article entries for the files
        articles (mapv #(merge
                         {:article-id
                          (add-article
                           {:primary-title (:filename %)}
                           project-id
                           source-id)}
                         %) zip-file-maps)]
    (try
      (when-not (nil? articles)
        (doall (mapv #(save-article-pdf %)
                     articles))
        (sources/update-project-source-metadata!
         source-id
         (assoc meta :importing-articles? false)))
      (catch Throwable e
        (sources/fail-project-source-import! source-id)
        (throw (Exception. "Error in sysrev.import.zip/import-pdfs-from-zip-file!: " (.getMessage e))))
      )))

