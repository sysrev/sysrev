(ns sysrev.subs.core
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe reg-sub reg-sub-raw]]
   [sysrev.base :refer [active-route]]
   [sysrev.shared.util :refer [in?]]))

;; These may be used for subscriptions in which nil may be returned as a
;; valid value.
(def not-found-value :not-found)
;;
(defn try-get [db path]
  (let [path (if (sequential? path) path [path])]
    (get-in db path not-found-value)))

(reg-sub
 :app-name
 (fn [db]
   (:app-name db)))

(defn get-csrf-token [db] (:csrf-token db))
(reg-sub :csrf-token get-csrf-token)

(reg-sub
 :active-notification
 (fn [db]
   nil))

(reg-sub
 :initialized?
 :<- [:have-identity?]
 :<- [:active-panel]
 (fn [[have-identity? active-panel]]
   (boolean (and have-identity? active-panel))))
