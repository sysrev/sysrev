(ns sysrev.luckyorange
  (:require [clojure.string :as str]
            [re-frame.core :refer [reg-event-fx reg-fx subscribe]]
            [reagent.core :as r]
            [sysrev.action.core :refer [def-action]]))

; TODO - we should be able to subscribe to changes in :user/display directly but seems like track! doesn't work on subscribe items
(def user-display-atom (r/cursor re-frame.db/app-db [:state :identity :email]))

(defn send-luckyorange-update [email]
  (let [name (first (str/split @email #"@"))]
    (.log js/console (str "chewy name is: " name " email is " @email))
    (if @email
      (do
        (if-not js/window._loq (set! js/window._loq (clj->js [])))
        (.log js/console (str "name is: " name " email is " @email))
        (-> ["custom",{:name name :email @email}] clj->js js/window._loq.push)))))

(r/track! send-luckyorange-update user-display-atom)