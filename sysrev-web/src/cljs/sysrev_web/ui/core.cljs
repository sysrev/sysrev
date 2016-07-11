(ns sysrev-web.ui.core
    (:require [sysrev-web.base :refer [state history]]
              [sysrev-web.ajax :as ajax]
              [pushy.core :as pushy]
              [reagent.core :as r]))

;; This method can be used if React lifecycle actions such as
;; :component-did-mount are needed, eg. for
;; running Javascript code to initialize SemanticUI module components.
(defn lifecycle-component-example []
  (r/create-class
   {:reagent-render
    (fn []
      [:div [:p "the state is " (str @state)]])
    :component-did-mount
    #(println (str "component mounted: " (r/dom-node %)))
    :component-did-update
    #(println (str "component updated: " (r/dom-node %)))}))

(defn main-content []
  [:div.main-content
   [:h2 "Reagent loaded"]
   [:h3 ". . ."]
   [lifecycle-component-example]])


