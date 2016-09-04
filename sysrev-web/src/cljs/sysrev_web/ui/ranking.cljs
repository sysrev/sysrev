(ns sysrev-web.ui.ranking
  (:require [sysrev-web.base :refer [state server-data]]))

(defn ranking-article-ids [page-num]
  (get-in @server-data [:ranking :pages page-num]))
