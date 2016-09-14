(ns sysrev-web.main
  (:require [sysrev-web.base :refer [history-init]]
            [sysrev-web.state.core :refer [init-state]]
            [sysrev-web.routes :as routes]
            [sysrev-web.ui.core :refer [main-content]]
            [reagent.core :as r]))

(defn ^:export run []
  (r/render
   [main-content]
   (js/document.getElementById "app")))

(defonce started
  (do (enable-console-print!)
      (init-state)
      (history-init)
      true))

(run)
