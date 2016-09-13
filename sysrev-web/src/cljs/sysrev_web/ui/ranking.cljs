(ns sysrev-web.ui.ranking
  (:require [sysrev-web.base :refer [state]]))

(defn ranking-article-ids [page-num]
  (get-in @state [:data :ranking :pages page-num]))
