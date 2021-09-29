(ns sysrev.file-util.core
  (:import (java.io InputStream)
           (java.nio.file CopyOption Files Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.util.zip ZipEntry ZipFile)
           (org.apache.commons.io IOUtils)))

(set! *warn-on-reflection* true)

(defn ^Path create-temp-file! [prefix suffix]
  (Files/createTempFile prefix suffix (make-array FileAttribute 0)))

(defmacro with-temp-file [[name-sym {:keys [prefix suffix]}] & body]
  `(let [~name-sym (create-temp-file! ~prefix ~suffix)]
     (try
       ~@body
       (finally
         (Files/delete ~name-sym)))))

(def standard-copy-options
  {:atomic-move StandardCopyOption/ATOMIC_MOVE
   :copy-attributes StandardCopyOption/COPY_ATTRIBUTES
   :replace-existing StandardCopyOption/REPLACE_EXISTING})

(defn copy! [^InputStream input-stream ^Path path copy-options]
  (->> (map #(standard-copy-options % %) copy-options)
       ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption)
       (Files/copy input-stream path)))

; We use ZipFile instead of ZipInputstream because zips have a central
; directory at the end, which ZipInputStream incorrectly ignores:
; https://en.wikipedia.org/wiki/ZIP_(file_format)#Structure
(defn read-zip-entries [^Path path]
  (with-open [zip (ZipFile. (.toFile path))]
    (->> zip .entries iterator-seq
         (map (fn [^ZipEntry entry]
                (let [name (.getName entry)]
                  [name (IOUtils/toByteArray (.getInputStream zip entry))])))
         (into {}))))
