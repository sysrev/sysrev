(ns sysrev.views.panels.password-reset
  (:require
   [re-frame.core :refer
    [subscribe dispatch dispatch-sync reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.views.base :refer [panel-content logged-out-content]]))

(defn reset-password-panel []
  [:div])

(defn request-password-reset-panel []
  [:div])

(defmethod panel-content [:request-password-reset] []
  (fn [child]
    [request-password-reset-panel]))

(defmethod logged-out-content [:request-password-reset] []
  (fn [child]
    [request-password-reset-panel]))

(defmethod panel-content [:reset-password] []
  (fn [child]
    [reset-password-panel]))

(defmethod logged-out-content [:reset-password] []
  (fn [child]
    [reset-password-panel]))
