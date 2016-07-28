(ns sysrev-web.main
    (:require [sysrev-web.base :refer [history]]
              [sysrev-web.ui.core :refer [main-content]]
              [pushy.core :as pushy]
              [reagent.core :as r]))

(defonce started
  (pushy/start! history))


(defn ^:export run []
  (r/render
   [main-content]
   (js/document.getElementById "app")))

(run)
