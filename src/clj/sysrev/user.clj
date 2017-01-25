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
        ;; sysrev.spark.core
        ;; sysrev.spark.similarity
        sysrev.import.pubmed
        sysrev.predict.core
        sysrev.predict.report
        sysrev.predict.validate
        sysrev.web.core
        sysrev.web.session
        sysrev.web.index
        sysrev.web.app
        sysrev.web.routes.site
        sysrev.web.routes.auth
        sysrev.web.routes.project
        sysrev.mail.core
        sysrev.custom.immuno
        sysrev.misc
        sysrev.init
        sysrev.shared.util
        sysrev.shared.keywords)
  (:require [clojure.math.numeric-tower :as math]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [config.core :refer [env]]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.xml :as dxml]
            [clojure.string :as str]
            ;; [flambo.api :as f]
            ;; [flambo.conf :as fc]
            ;; [flambo.tuple :as ft]
            ;; [flambo.sql :as fsql]
            ))

(defonce started
  (sysrev.init/start-app))
