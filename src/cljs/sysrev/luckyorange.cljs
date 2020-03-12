(ns sysrev.luckyorange
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]))

;; TODO - we should be able to subscribe to changes in :user/display
;; directly but seems like track! doesn't work on subscribe items
(def user-display-atom (r/cursor app-db [:state :identity :email]))

(defn send-luckyorange-update [email]
  (let [name (first (str/split @email #"@"))]
    (.log js/console (str "chewy name is: " name " email is " @email))
    (when @email
      (if-not js/window._loq (set! js/window._loq (clj->js [])))
      (.log js/console (str "name is: " name " email is " @email))
      (-> ["custom",{:name name :email @email}] clj->js js/window._loq.push))))

(r/track! send-luckyorange-update user-display-atom)
