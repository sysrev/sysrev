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
        sysrev.db.transfer
        sysrev.db.export
        sysrev.db.files
        sysrev.db.sources
        sysrev.clone-project
        sysrev.files.stores
        sysrev.import.pubmed
        sysrev.import.endnote
        sysrev.export.endnote
        sysrev.predict.core
        sysrev.predict.report
        sysrev.biosource.predict
        sysrev.biosource.importance
        sysrev.web.core
        sysrev.web.session
        sysrev.web.index
        sysrev.web.app
        sysrev.web.routes.site
        sysrev.web.routes.auth
        sysrev.web.routes.project
        sysrev.web.routes.api.core
        sysrev.web.routes.api.handlers
        sysrev.mail.core
        sysrev.custom.immuno
        sysrev.custom.facts
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
        sysrev.test.browser.core)
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.math.numeric-tower :as math]
            [clojure.java.jdbc :as j]
            [cljs.env :as env]
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
  (sysrev.init/start-app))
