(ns sysrev.luckyorange
  (:require [reagent.core :as r]
            [re-frame.db :refer [app-db]]))


; TODO - we should be able to subscribe to changes in :user/display directly
(def user-display-atom (r/cursor app-db [:state :identity :email]))
;(def user-display-atom (subscribe [:user/display]))

(defn send-luckyorange-update [email]
  (when email
    (when-not js/window._loq
      (set! js/window._loq (clj->js [])))
    (js/window._loq.push (clj->js ["custom" {:email email}]))))

(add-watch user-display-atom :luckyorange-watch
           (fn [_ _ _old-state new-state]
             (send-luckyorange-update new-state)))

(send-luckyorange-update user-display-atom)
