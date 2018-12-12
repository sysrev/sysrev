;; Used to ensure all entity definition namespaces are loaded
;;
;; This namespace is required from sysrev.init which should itself
;; always be loaded

(ns sysrev.all-entities
  (:require [sysrev.article.entity]))
