(ns sysrev.config.interface
  (:require [sysrev.config.core :as core]))

(defn get-config
  "Parses and returns the EDN resource file at filename.

  It's recommended to name config files as (project)-config.edn so that
  there can be multiple config files when one project is embedded within
  another. E.g., a datapub server is embedded within the sysrev dev
  environment for testing purposes, and its config lives at
  datapub-config.edn. This would not work if datapub and sysrev both used
  config.edn for their config file name."
  [filename]
  (core/get-config filename))
