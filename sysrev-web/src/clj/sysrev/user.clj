(ns sysrev.user
  (:use sysrev.util
        sysrev.db.core
        sysrev.db.articles
        sysrev.db.users
        sysrev.db.sysrev
        sysrev.web.core
        sysrev.web.ajax
        sysrev.web.session
        sysrev.web.auth
        sysrev.web.index)
  (:require [sysrev.init :as init]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

(defn reload []
  (require 'sysrev.user :reload))

(defonce started
  (do (set-db-config!)
      (println "connected to postgres")
      (run-web 4041)
      (println "web server started (port 4041)")
      true))
