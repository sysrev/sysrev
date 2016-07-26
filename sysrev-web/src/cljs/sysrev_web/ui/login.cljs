(ns sysrev-web.ui.login
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.react.components :refer [link]]
            [sysrev-web.routes :as routes]))

(defn login []
  (fn []
    [:div
     [:div "Log in. !"]
     [link routes/home "Go to /"]]))

