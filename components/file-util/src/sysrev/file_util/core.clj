(ns sysrev.file-util.core
  (:import (java.io InputStream)
           (java.nio.file CopyOption Files Path Paths StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.util.zip ZipEntry ZipFile)
           (org.apache.commons.io IOUtils)))

(defn get-path [^String first & more]
  (Paths/get first (into-array String more)))

(defn ^Path create-temp-file! [prefix suffix]
  (Files/createTempFile prefix suffix (make-array FileAttribute 0)))

(defmacro with-temp-file [[name-sym {:keys [prefix suffix]}] & body]
  `(let [~name-sym (create-temp-file! ~prefix ~suffix)]
     (try
       ~@body
       (finally
         (Files/delete ~name-sym)))))

(defmacro with-temp-files [[name-sym {:keys [num-files prefix suffix]}] & body]
  `(let [file-seq# (vec
                    (repeatedly
                     ~num-files
                     (partial create-temp-file! ~prefix ~suffix)))
         ~name-sym file-seq#]
     (try
       ~@body
       (finally
         (doseq [f# file-seq#]
           (Files/delete f#))))))

(def standard-copy-options
  {:atomic-move StandardCopyOption/ATOMIC_MOVE
   :copy-attributes StandardCopyOption/COPY_ATTRIBUTES
   :replace-existing StandardCopyOption/REPLACE_EXISTING})

(defn copy! [is-or-path ^Path path copy-options]
  (let [f (cond
            (instance? InputStream is-or-path) #(Files/copy ^InputStream is-or-path path %)
            (instance? Path is-or-path) #(Files/copy ^Path is-or-path path %))]
    (->> (map #(standard-copy-options % %) copy-options)
         ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption)
         f)))

(defn ^Path create-directories! [^Path dir]
  (Files/createDirectories dir (make-array FileAttribute 0)))

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
