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
            [clojure.data.json :as json]
            [config.core :refer [env]]))

(defn reload []
  (require 'sysrev.user :reload))

(defonce started
  (let [{postgres-config :postgres} env
        {{postgres-port :port} :postgres
         {server-port :port} :server} env]
    (do (set-db-config! postgres-config)
        (->> postgres-port (format "connected to postgres (port %s)") println)
        (run-web server-port)
        (->> server-port (format "web server started (port %s)") println)
        true)))
