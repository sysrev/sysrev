(ns sysrev.postgres.embedded
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.opentable.db.postgres.embedded EmbeddedPostgres EmbeddedPostgres$Builder PgBinaryResolver PgDirectoryResolver)))

(def binary-resolver
  (reify PgBinaryResolver
    (^java.io.InputStream getPgBinary [this ^String system ^String machine-hardware]
     (-> (format "postgres-%s-%s.txz" system machine-hardware)
         str/lower-case
         io/resource
         .openStream))))

(def directory-resolver
  (reify PgDirectoryResolver
    (getDirectory [this _override-working-directory]
      (io/file (System/getenv "POSTGRES_DIRECTORY")))))

(defn ^EmbeddedPostgres$Builder embedded-pg-builder [port]
  (if (System/getenv "POSTGRES_DIRECTORY")
    (-> (EmbeddedPostgres/builder)
        (.setPgDirectoryResolver directory-resolver)
        (.setPort port))
    (-> (EmbeddedPostgres/builder)
        (.setPgBinaryResolver binary-resolver)
        (.setPort port))))

(defn ^EmbeddedPostgres start! [^EmbeddedPostgres$Builder embedded-pg-builder]
  (.start embedded-pg-builder))

(defn stop! [^EmbeddedPostgres embedded-pg]
  (.close embedded-pg))

(defn get-port [^EmbeddedPostgres embedded-pg]
  (.getPort embedded-pg))
