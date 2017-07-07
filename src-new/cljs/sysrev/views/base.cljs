(ns sysrev.views.base
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]))

(defn- active-panel []
  @(subscribe [:active-panel]))

(defmulti panel-content active-panel)
(defmulti logged-out-content active-panel)
