(ns sysrev-web.main
    (:require [sysrev-web.base :as base :refer [state history]]
              [sysrev-web.ajax :as ajax]
              [sysrev-web.routes :as routes]
              [sysrev-web.ui.core :as ui :refer [main-content]]
              [pushy.core :as pushy]
              [reagent.core :as r]
              [devtools.core :as devtools]))

(defonce started
  (pushy/start! history))

(devtools/set-pref! :install-sanity-hints true)
(if ^boolean goog/DEBUG (devtools/install!))

(defn ^:export run []
  (r/render
   [main-content]
   (js/document.getElementById "app")))

(run)
