(ns sysrev-web.react.components
  (:require [sysrev-web.base :refer [nav!]]))

(defn replace-event
  "Creates a function that takes an event, calls preventDefault, and runs the provided handler"
  [handler]
  (fn [e]
    (. e (preventDefault))
    (handler)))

(defn base-link
  "Constructs a link which blocks normal link behavior and runs a handler
  todo: could probably upgrade this to allow href for no-javascript browsers."
  [{:keys [class]} route-f handler child]
  [:a {:class class :style {:cursor "pointer"} :on-click handler} child])

(defn link
  "Constructs a link which blocks normal link behavior and runs a navigation through secretary
  todo: could also extend this to provide href and handle cases where browsers do not support history navigation"
  ([{:keys [class]} route-f child]
   (let [handler (replace-event #(nav! route-f))]
     [base-link {:class class} route-f handler child]))
  ([route-f child]
   (link {:class "ui link"} route-f child)))

(defn link-nonav
  "Javascript action only, without route changes."
  ([{:keys [class]} route-f child]
   (let [handler (replace-event route-f)]
      [base-link {:class class} #(str "#") handler child]))
  ([route-f child]
   (link-nonav {:class "ui link"} route-f child)))

