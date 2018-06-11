(ns sysrev.import.zip
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [sysrev.util :as util])
  (:import [java.util.zip ZipFile ZipInputStream]))


(defn file->zip-file
  [^java.io.File file]
  (ZipFile. file))

(defn zip-file-map
  "Given a zip-file and zip-entries, return the {:name <file> :byte-array byte[]} for
  each entry"
  ([^java.util.zip.ZipFile zip-file zip-file-entries]
   (zip-file-map zip-file zip-file-entries []))
  ([^java.util.zip.ZipFile zip-file zip-file-entries result]
   (let [zip-entry (first zip-file-entries)]
     (if (empty? zip-file-entries)
       result
       (zip-file-map zip-file (rest zip-file-entries)
                     (conj result
                           (hash-map :name (.getName zip-entry)
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

(defn pdf-zip-file->zip-file-map
  "Given a zip file with pdfs, extract only the pdfs into a zip-file-map"
  [^java.io.File file]
  (let [pdf-zip-file (file->zip-file file)]
    (zip-file-map pdf-zip-file
                  (pdf-zip-entries pdf-zip-file))))
;;(map #(util/byte-array->sha-1-hash (:byte-array %)) (pdf-zip-file->zip-file-map  (java.io.File. "pdfs.zip")))
