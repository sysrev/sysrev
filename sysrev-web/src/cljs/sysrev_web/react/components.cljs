(ns sysrev-web.react.components
  (:require [sysrev-web.base :refer [history]]
            [pushy.core :refer [replace-token!]]))

(defn link
  ([{:keys [class]} route-f child]
   (let [handler (fn [e]
                   (. e (preventDefault))
                   (replace-token! history (route-f)))]
     [:a {:class class :style {:cursor "pointer"} :on-click handler} child]))
  ([route-f child]
   (link {:class "ui link"} route-f child)))
