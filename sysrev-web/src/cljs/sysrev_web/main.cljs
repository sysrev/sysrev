(ns sysrev-web.main
    (:require [sysrev-web.base :refer [history-init]]
              [sysrev-web.ui.core :refer [main-content]]
              [reagent.core :as r]))

(defonce started
  (history-init))

(defn ^:export run []
  (r/render
   [main-content]
   (js/document.getElementById "app")))

(run)
