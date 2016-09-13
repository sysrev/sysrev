(ns sysrev-web.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]
            [cljs.pprint :refer [pprint]]))

(secretary/set-config! :prefix "")

(defonce history
  (pushy/pushy secretary/dispatch!
               (fn [x] (when (secretary/locate-route x) x))))

(defn history-init []
  (pushy/start! history))

;; Contains all app state and data pulled from server
(defonce ^:dynamic state (r/atom {}))
