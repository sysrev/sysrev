(ns sysrev.main
  (:require [sysrev.base :refer [history-init]]
            [sysrev.state.core :refer [init-state]]
            [sysrev.routes :as routes]
            [sysrev.ui.core :refer [main-content]]
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
