(ns sysrev-web.main
  (:require [sysrev-web.base :refer [history-init]]
            [sysrev-web.routes :as routes]
            [sysrev-web.ui.core :refer [main-content]]
            [reagent.core :as r]))

(enable-console-print!)

(defonce started
  (history-init))

(defn ^:export run []
  (r/render
   [main-content]
   (js/document.getElementById "app")))

(run)
