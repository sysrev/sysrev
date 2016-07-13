(ns sysrev-web.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]))

(defonce state (r/atom nil))
(defonce server-data (r/atom {}))

(defn state-val [ks]
  (get-in @state ks))

(defn state-set [ks val]
  (swap! state #(assoc-in % ks val)))

(enable-console-print!)

(secretary/set-config! :prefix "/")

(defonce history
  (pushy/pushy secretary/dispatch!
               (fn [x] (when (secretary/locate-route x) x))))
