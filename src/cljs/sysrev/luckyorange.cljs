(ns sysrev.luckyorange
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.db :refer [app-db]]))

(defn send-luckyorange-update [email-var]
  (when-let [email @email-var]
    (let [name (first (str/split email #"@"))]
      (or js/window._loq (set! js/window._loq (clj->js [])))
      (js/window._loq.push (clj->js ["custom" {:name name :email email}])))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defonce luckyorange-tracker
  (r/track! send-luckyorange-update
            (r/cursor app-db [:state :identity :email])))
