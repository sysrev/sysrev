(ns sysrev.user
  (:refer-clojure :exclude [find])
  (:use sysrev.logging
        sysrev.util
        sysrev.db.core
        sysrev.db.entity
        sysrev.db.migration
        sysrev.user.core
        sysrev.group.core
        sysrev.article.core
        sysrev.article.assignment
        sysrev.label.core
        sysrev.label.migrate
        sysrev.annotations
        sysrev.datasource.core
        sysrev.datasource.api
        sysrev.project.core
        sysrev.project.member
        sysrev.project.description
        sysrev.project.clone
        sysrev.project.article-list
        sysrev.formats.pubmed
        sysrev.export.core
        sysrev.export.endnote
        sysrev.source.core
        sysrev.source.endnote
        sysrev.source.pdf-zip
        sysrev.source.ris
        sysrev.source.import
        sysrev.payment.paypal
        sysrev.payment.stripe
        sysrev.payment.plans
        sysrev.file.core
        sysrev.file.s3
        sysrev.file.article
        sysrev.file.user-image
        sysrev.file.document
        sysrev.predict.core
        sysrev.predict.report
        sysrev.biosource.predict
        sysrev.biosource.importance
        sysrev.biosource.annotations
        sysrev.biosource.duplicates
        sysrev.slack
        sysrev.web.core
        sysrev.web.session
        sysrev.web.index
        sysrev.web.app
        sysrev.web.build
        sysrev.web.routes.site
        sysrev.web.routes.auth
        sysrev.web.routes.project
        sysrev.web.routes.api.core
        sysrev.web.routes.api.handlers
        sysrev.mail.core
        #_ sysrev.custom.immuno
        #_ sysrev.custom.ebtc
        #_ sysrev.custom.insilica
        sysrev.misc
        sysrev.init
        sysrev.shared.keywords
        sysrev.shared.transit
        sysrev.test.core
        sysrev.test.browser.navigate)
  (:require [clojure.spec.alpha :as s]
            [orchestra.spec.test :as t]
            [clojure.math.numeric-tower :as math]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [clojure.java.shell :as shell :refer [sh]]
            [clj-time.core :as time]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [clj-http.client :as http]
            [clj-webdriver.taxi :as taxi]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.test.junit :refer :all]
            [clojure.zip :as zip]
            [clojure.data.xml :as dxml]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [clojure-csv.core :as csv]
            [amazonica.core :as aws]
            [amazonica.aws.s3 :as s3]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update delete]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.config :refer [env]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.users :as su]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.notes :as snt]
            sysrev.test.all
            [sysrev.db.queries :as q]
            [sysrev.db.query-types :as qt]
            [sysrev.api :as api]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.test.browser.core :refer :all :exclude [wait-until]])
  (:import java.util.UUID))

(defonce started
  (try
    (sysrev.init/start-app)
    (catch Throwable e
      (log/info "error in sysrev.init/start-app")
      (log/info (.getMessage e))
      (.printStackTrace e))))
