(ns sysrev.user
  (:use sysrev.util
        sysrev.db.core
        sysrev.db.articles
        sysrev.db.documents
        sysrev.db.users
        sysrev.db.sysrev
        sysrev.db.endnote
        sysrev.db.predict
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
            [config.core :refer [env]]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.xml :as dxml]
            [clojure.string :as str]))

(defn reload []
  (require 'sysrev.user :reload))

(defonce started
  (init/start-app))
