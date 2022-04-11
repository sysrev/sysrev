(ns sysrev.source.pdf-zip
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [sysrev.file.article :as article-file]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.util :as util])
  (:import [org.apache.commons.compress.archivers.zip ZipArchiveEntry ZipFile ZipArchiveEntry]))

(defn to-zip-file [^java.io.File file] (ZipFile. file))

;;; https://stackoverflow.com/questions/5419125/reading-a-zip-file-using-java-api-from-clojure
;;; https://commons.apache.org/proper/commons-compress/javadocs/api-1.18/index.html
(defn pdf-zip-entries
  [^ZipFile zip-file]
  (->> (enumeration-seq (.getEntries zip-file))
       (filter
        (fn [^ZipArchiveEntry entry]
          (let [name (.getName entry)]
            (and name
                 (fs/base-name name)
                 ;; exclude files where name starts with "."
                 (not (str/starts-with? (fs/base-name name) "."))
                 ;; filter by pdf file extension
                 (= ".pdf" (some-> name fs/extension str/lower-case))
                 ;; there seems to be spurious entries; filter based on size
                 (> (.getSize entry) 256)))))))

(defn- lookup-filename-sources [project-id filename]
  (->> (source/project-sources project-id)
       (filter #(= filename (get-in % [:meta :filename])))))

;; FIX: want this to return an error if no pdfs found - does it?
(defmethod import-source :pdf-zip
  [sr-context _ project-id {:keys [file filename]} {:as options}]
  (let [filename-sources (lookup-filename-sources project-id filename)]
    (if (seq filename-sources)
      (do (log/warn "import-source pdf-zip - non-empty filename-sources -" filename-sources)
          {:error {:message "File name already imported"}})
      (let [^ZipFile zip-file (-> file to-zip-file)
            source-meta {:source "PDF Zip file" :filename filename}
            pdf-to-article (fn [^ZipArchiveEntry zip-entry]
                             {:filename (fs/base-name (.getName zip-entry))
                              :file-byte-array (-> (.getInputStream zip-file zip-entry)
                                                   (util/slurp-bytes))})
            impl {:types {:article-type "file" :article-subtype "pdf"}
                  :get-article-refs #(-> zip-file pdf-zip-entries)
                  :get-articles #(map pdf-to-article %)
                  :on-article-added #(article-file/save-article-pdf
                                      (-> (select-keys % [:article-id :filename])
                                          (assoc :file-bytes (:file-byte-array %))))
                  :prepare-article #(-> (set/rename-keys % {:filename :primary-title})
                                        (dissoc :file-byte-array))}]
        (import-source-impl sr-context project-id source-meta impl options
                            :filename filename :file file)))))
