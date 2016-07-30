(ns sysrev-web.ui.user
  (:require [sysrev-web.routes :as routes]
            [sysrev-web.react.components :refer [link]]))

(defn user []
  (fn []
    [:div
     [:div "USER !"]
     [link routes/home "Go to /"]]))
