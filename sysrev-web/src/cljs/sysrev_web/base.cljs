(ns sysrev-web.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]
            [cljs.pprint :refer [pprint]]))

(defonce state (r/atom {}))
(defonce server-data (r/atom {}))

(def debug true)

(defn state-val [ks]
  (get-in @state ks))

(defn state-set [ks val]
  (swap! state #(assoc-in % ks val)))

(enable-console-print!)

(defn debug-container [children]
  (fn [children]
   [:div {:style {:background-color "lightgrey"}} children]))

(defn show-debug-box
  (
   [title obj]
   [debug-container [:h1 title] [:pre (with-out-str (pprint obj))]])
  (
   [obj]
   [debug-container [:pre (with-out-str (pprint obj))]]))

(defn debug-box [arg & args]
  (when debug (apply show-debug-box arg args)))


(secretary/set-config! :prefix "/")

(defonce history
  (pushy/pushy secretary/dispatch!
               (fn [x] (when (secretary/locate-route x) x))))
