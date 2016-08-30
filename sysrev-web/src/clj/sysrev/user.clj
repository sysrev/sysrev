(ns sysrev.user
  (:use sysrev.util
        sysrev.db.core
        sysrev.db.articles
        sysrev.db.users
        sysrev.web.core
        sysrev.web.ajax
        sysrev.web.index)
  (:require [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

(defn reload []
  (require 'sysrev.user :reload))

(defonce started
  (do (set-db-config!)
      (println "connected to postgres")
      (run-web 4041)
      (println "web server started (port 4041)")
      true))
