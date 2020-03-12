(ns sysrev.luckyorange
  (:require [re-frame.core :refer [reg-event-fx reg-fx subscribe]]
            [reagent.core :as r]
            [sysrev.action.core :refer [def-action]]))

; TODO - we should be able to subscribe to changes in :user/display directly
(def user-display-atom (r/cursor re-frame.db/app-db [:state :identity :email]))
;(def user-display-atom (subscribe [:user/display]))

(defn send-luckyorange-update [email]
  (if email
    (do
      (if-not js/window._loq (set! js/window._loq (clj->js [])))
      (-> ["custom",{:email email}] clj->js js/window._loq.push))))

(add-watch user-display-atom :luckyorange-watch
           (fn [_ _ old-state new-state]
             (send-luckyorange-update new-state)))

(send-luckyorange-update user-display-atom)