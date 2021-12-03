(ns sysrev.source.pdf-zip
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [sysrev.file.article :as article-file]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.util :as util])
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

(defn- lookup-filename-sources [project-id filename]
  (->> (source/project-sources project-id)
       (filter #(= filename (get-in % [:meta :filename])))))

(defmethod make-source-meta :pdf-zip [_ {:keys [filename]}]
  {:source "PDF Zip file" :filename filename})

;; FIX: want this to return an error if no pdfs found - does it?
(defmethod import-source :pdf-zip
  [request _ project-id {:keys [file filename]} {:as options}]
  (let [filename-sources (lookup-filename-sources project-id filename)]
    (if (seq filename-sources)
      (do (log/warn "import-source pdf-zip - non-empty filename-sources -" filename-sources)
          {:error {:message "File name already imported"}})
      (let [^ZipFile zip-file (-> file to-zip-file)
            source-meta (make-source-meta :pdf-zip {:filename filename})
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
        (import-source-impl request project-id source-meta impl options
                            :filename filename :file file)))))
