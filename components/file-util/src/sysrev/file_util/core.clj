(ns sysrev.file-util.core
  (:import (java.io InputStream)
           (java.nio.file Files Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

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
       ^"[Ljava.nio.file.StandardCopyOption;" (into-array StandardCopyOption)
       (Files/copy input-stream path)))
