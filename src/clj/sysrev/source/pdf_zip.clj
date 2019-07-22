(ns sysrev.source.pdf-zip
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [sysrev.db.core :as db :refer [with-transaction]]
            [sysrev.article.core :as article]
            [sysrev.db.files :as files]
            [sysrev.filestore :as fstore]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.util :as util :refer [shell]])
  (:import [org.apache.commons.compress.archivers.zip ZipFile ZipArchiveEntry]))

(defn to-zip-file [^java.io.File file] (ZipFile. file))

;;; https://stackoverflow.com/questions/5419125/reading-a-zip-file-using-java-api-from-clojure
;;; https://commons.apache.org/proper/commons-compress/javadocs/api-1.18/index.html
(defn pdf-zip-entries
  [^ZipFile zip-file]
  (->> (enumeration-seq (.getEntries zip-file))
       (filter #(.getName %))
       (filter #(fs/base-name (.getName %)))
       ;; exclude files where name starts with "."
       (filter #(not (some-> (fs/base-name (.getName %))
                             (str/starts-with? "."))))
       ;; filter by pdf file extension
       (filter #(= ".pdf" (some-> (.getName %) fs/extension str/lower-case)))
       ;; there seems to be spurious entries; filter based on size
       (filter #(> (.getSize %) 256))))

(defn save-article-pdf
  "Save PDF to S3 and associate with article."
  [{:keys [article-id filename byte-array]}]
  (with-transaction
    (let [file-hash (util/byte-array->sha-1-hash byte-array)
          s3-id (files/s3-id-from-filename-key filename file-hash)
          associated? (files/s3-article-association? s3-id article-id)]
      (cond
        ;; file is already associated with this article
        associated? nil

        ;; file exists but not associated with this article
        s3-id (files/associate-s3-file-with-article s3-id article-id)

        ;; file exists but under a different name
        (files/s3-has-key? file-hash)
        (do (files/insert-file-hash-s3-record filename file-hash)
            (-> (files/s3-id-from-filename-key filename file-hash)
                (files/associate-s3-file-with-article article-id)))

        ;; file does not exist on s3
        :else
        (do (fstore/save-byte-array byte-array :pdf)
            (files/insert-file-hash-s3-record filename file-hash)
            (-> (files/s3-id-from-filename-key filename file-hash)
                (files/associate-s3-file-with-article article-id))))
      {:result {:success true :key file-hash :filename filename}})))

(defmethod make-source-meta :pdf-zip [_ {:keys [filename]}]
  {:source "PDF Zip file" :filename filename})

;; FIX: want this to return an error if no pdfs found - does it?
(defmethod import-source :pdf-zip
  [stype project-id {:keys [file filename]} {:as options}]
  (let [project-sources (source/project-sources project-id)
        filename-sources (->> project-sources
                              (filter #(= (get-in % [:meta :filename]) filename)))]
    (cond
      (not-empty filename-sources)
      (do (log/warn "import-source pdf-zip - non-empty filename-sources - "
                    filename-sources)
          {:error {:message "File name already imported"}})

      :else
      (let [^ZipFile zip-file (-> file to-zip-file)
            source-meta (make-source-meta :pdf-zip {:filename filename})
            pdf-to-article
            (fn [^ZipArchiveEntry zip-entry]
              {:filename (fs/base-name (.getName zip-entry))
               :file-byte-array (-> (.getInputStream zip-file zip-entry)
                                    (util/slurp-bytes))})
            impl {:get-article-refs #(-> zip-file pdf-zip-entries)
                  :get-articles #(map pdf-to-article %)
                  :on-article-added
                  #(save-article-pdf
                    (-> %
                        (select-keys [:article-id :filename])
                        (assoc :byte-array (:file-byte-array %))))
                  :prepare-article
                  #(-> %
                       (set/rename-keys {:filename :primary-title})
                       (dissoc :file-byte-array))}]
        (import-source-impl project-id source-meta impl options
                            :filename filename :file file)))))
