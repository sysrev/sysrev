(ns datapub.postgres-embedded
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.opentable.db.postgres.embedded EmbeddedPostgres EmbeddedPostgres$Builder PgBinaryResolver)))

(set! *warn-on-reflection* true)

(def resolver
  (reify PgBinaryResolver
    (^java.io.InputStream getPgBinary [this ^String system ^String machine-hardware]
      (-> (format "postgres-%s-%s.txz" system machine-hardware)
          str/lower-case
          io/resource
          .openStream))))

(defn ^EmbeddedPostgres$Builder embedded-pg-builder [port]
  (-> (EmbeddedPostgres/builder)
      (.setPgBinaryResolver resolver)
      (.setPort port)))

(defn ^EmbeddedPostgres start! [^EmbeddedPostgres$Builder embedded-pg-builder]
  (.start embedded-pg-builder))

(defn stop! [^EmbeddedPostgres embedded-pg]
  (.close embedded-pg))

(defn get-port [^EmbeddedPostgres embedded-pg]
  (.getPort embedded-pg))
