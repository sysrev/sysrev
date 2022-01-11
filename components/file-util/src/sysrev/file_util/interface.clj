(ns sysrev.file-util.interface
  (:require
   [sysrev.file-util.core :as core])
  (:import
   (java.nio.file Path)))

(defn copy!
  "Copies an input-stream or a path to `path`.

  copy-options should be a seq whose values can be any
  java.nio.file.CopyOption object or a value in
  #{:atomic-move :copy-attributes :replace-existing}."
  [is-or-path ^Path path copy-options]
  (core/copy! is-or-path path copy-options))

(defn ^Path create-directories!
  "Creates a directory by creating all nonexistent parent directories first."
  [^Path dir]
  (core/create-directories! dir))

(defn get-path
  "Converts a path string, or a sequence of strings that when joined form
  a path string, to a Path.

  See https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/FileSystem.html#getPath(java.lang.String,java.lang.String...)"
  [^String first & more]
  (apply core/get-path first more))

(defn read-zip-entries
  "Returns a map of {filename byte-array}."
  [^Path path]
  (core/read-zip-entries path))

(defmacro with-temp-file
  "Creates a file and binds name-sym to its Path."
  [[name-sym {:keys [prefix suffix]}] & body]
  `(core/with-temp-file [~name-sym {:prefix ~prefix :suffix ~suffix}]
     ~@body))

(defmacro with-temp-files
  "Creates `num-files` files and binds name-sym to a vector of their Paths."
  [[name-sym {:keys [num-files prefix suffix] :as opt}] & body]
  `(core/with-temp-files [~name-sym ~opt]
     ~@body))
