(ns sysrev.user
  (:refer-clojure :exclude [find])
  (:use clojure.repl
        sysrev.logging
        sysrev.util
        sysrev.db.core
        sysrev.db.listeners
        sysrev.db.migration
        sysrev.user.interface
        sysrev.group.core
        sysrev.article.core
        sysrev.article.assignment
        sysrev.label.core
        sysrev.label.migrate
        sysrev.annotations
        sysrev.datasource.core
        sysrev.datasource.api
        sysrev.notification.interface
        sysrev.project.core
        sysrev.project.charts
        sysrev.project.member
        sysrev.project.description
        sysrev.project.clone
        sysrev.project.article-list
        sysrev.formats.pubmed
        sysrev.export.core
        sysrev.export.endnote
        [sysrev.main :as main :exclude [-main]]
        sysrev.source.core
        sysrev.source.endnote
        sysrev.source.pdf-zip
        sysrev.source.ris
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
        sysrev.biosource.concordance
        sysrev.biosource.countgroup
        sysrev.slack
        sysrev.auth.google
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
        sysrev.init
        sysrev.shared.keywords
        sysrev.stacktrace)
  (:require [clj-http.client :as http]
            [clj-time.coerce :as tc]
            [clj-time.core :as time]
            [clj-time.format :as tf]
            [clojure-csv.core :as csv]
            [clojure.data.json :as json]
            [clojure.data.xml :as dxml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as j]
            [clojure.java.shell :as shell :refer [sh]]
            [clojure.math.numeric-tower :as math]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.test.junit :refer :all]
            [clojure.tools.logging :as log]
            [clojure.zip :as zip]
            [cognitect.transit :as transit]
            [com.stuartsierra.component :refer (start stop)]
            hashp.core
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update delete]]
            [me.raynes.fs :as fs]
            [orchestra.spec.test :as st]
            [sysrev.api :as api]
            [sysrev.config :refer [env]]
            [sysrev.db.queries :as q]
            [sysrev.fixtures.interface :as fixtures]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.notes :as snt]
            [sysrev.shared.spec.project :as sp]
            [sysrev.test.core :as test]
            [sysrev.user.interface.spec :as su]))

(defn -main [& [port & args]]
  (let [port (some-> port Long/parseUnsignedLong)
        exit? (boolean (some #{"--exit"} args))]
    (try
      (st/instrument)
      (try
        (sysrev.init/start-app port)
        (fixtures/load-fixtures!)
        (catch Exception e
          (log/error "Exception in sysrev.init/start-app")
          (log/error (.getMessage e))
          (log/error (with-out-str (print-cause-trace-custom e)))))
      (main/start-nrepl! env)
      (spit ".nrepl-port" (:bound-port (:nrepl @main/nrepl)))
      (if exit?
        (do
          (main/stop!)
          (System/exit 0))
        (clojure.main/repl :init #(in-ns 'sysrev.user)))
      (catch Throwable e
        (if exit?
          (do
            (log/error e)
            (System/exit 1))
          (throw e))))))
