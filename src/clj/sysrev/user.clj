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
        sysrev.import.endnote
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
        sysrev.web.routes.api.core
        sysrev.web.routes.api.handlers
        sysrev.mail.core
        sysrev.custom.immuno
        sysrev.custom.facts
        sysrev.misc
        sysrev.init
        sysrev.shared.util
        sysrev.shared.keywords
        sysrev.test.core
        sysrev.test.browser.core)
  (:require [clojure.spec :as s]
            [clojure.spec.test :as t]
            [clojure.math.numeric-tower :as math]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [sysrev.config.core :refer [env]]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.test.junit :refer :all]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.xml :as dxml]
            [clojure.string :as str]
            ;; [flambo.api :as f]
            ;; [flambo.conf :as fc]
            ;; [flambo.tuple :as ft]
            ;; [flambo.sql :as fsql]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.users :as su]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.notes :as snt]
            sysrev.test.all)
  (:import java.util.UUID))

(defonce started
  (sysrev.init/start-app))
