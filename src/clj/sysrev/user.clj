(ns sysrev.user
  (:use sysrev.util
        sysrev.db.core
        sysrev.db.queries
        sysrev.db.articles
        sysrev.db.documents
        sysrev.db.users
        sysrev.db.labels
        sysrev.db.project
        sysrev.db.migration
        sysrev.db.export
        sysrev.db.files
        sysrev.source.core
        sysrev.db.article_list
        sysrev.db.annotations
        sysrev.cassandra
        sysrev.clone-project
        sysrev.files.stores
        sysrev.import.endnote
        sysrev.import.zip
        sysrev.export.endnote
        sysrev.paypal
        sysrev.predict.core
        sysrev.predict.report
        sysrev.biosource.predict
        sysrev.biosource.importance
        sysrev.biosource.annotations
        sysrev.biosource.duplicates
        sysrev.web.core
        sysrev.web.session
        sysrev.web.index
        sysrev.web.app
        sysrev.web.routes.site
        sysrev.web.routes.auth
        sysrev.web.routes.project
        sysrev.web.routes.api.core
        sysrev.web.routes.api.handlers
        sysrev.web.blog
        sysrev.mail.core
        sysrev.custom.immuno
        sysrev.custom.ebtc
        sysrev.custom.insilica
        sysrev.misc
        sysrev.init
        sysrev.resources
        sysrev.shared.util
        sysrev.shared.keywords
        sysrev.shared.transit
        sysrev.shared.article-list
        sysrev.test.core
        sysrev.test.browser.core
        sysrev.test.browser.navigate)
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.math.numeric-tower :as math]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [clojure.java.shell :as shell :refer [sh]]
            [clj-time.core :as time]
            [clj-time.coerce :as tc]
            [clj-http.client :as http]
            [clj-webdriver.taxi :as taxi]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.test.junit :refer :all]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.xml :as dxml]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [clojure-csv.core :as csv]
            [amazonica.core :as aws]
            [amazonica.aws.s3 :as s3]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.config.core :refer [env]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.users :as su]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.notes :as snt]
            sysrev.test.all
            [sysrev.db.queries :as q]
            [sysrev.api :as api])
  (:import java.util.UUID))

(try
  (require '[flambo.api :as f])
  (require '[flambo.conf :as fc])
  (require '[flambo.tuple :as ft])
  (require '[flambo.sql :as fsql])
  (use 'sysrev.spark.core)
  (use 'sysrev.spark.similarity)
  (catch Throwable e
    ;; Continue silently if Flambo dependencies are not loaded
    nil))

(defonce started
  (try
    (sysrev.init/start-app)
    (catch Throwable e
      (log/info "error in sysrev.init/start-app")
      (log/info (.getMessage e))
      (.printStackTrace e))))
