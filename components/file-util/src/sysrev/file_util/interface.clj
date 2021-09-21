(ns sysrev.file-util.interface
  (:require [sysrev.file-util.core :as core])
  (:import (java.io InputStream)
           (java.nio.file Path)))

(defn copy!
  "Copies input-stream to path.

  copy-options should be a seq whose values can be any
  java.nio.file.CopyOption object or a value in
  #{:atomic-move :copy-attributes :replace-existing}."
  [^InputStream input-stream ^Path path copy-options]
  (core/copy! input-stream path copy-options))

(defmacro with-temp-file
  "Creates a file and binds name-sym to its Path."
  [[name-sym {:keys [prefix suffix]}] & body]
  `(core/with-temp-file [~name-sym {:prefix ~prefix :suffix ~suffix}]
     ~@body))
