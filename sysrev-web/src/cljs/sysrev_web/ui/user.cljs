(ns sysrev-web.ui.user
  (:require [sysrev-web.routes :as routes]))

(defn user []
  (fn []
    [:div
     [:div "USER !"]
     [link routes/home "Go to /"]]))
