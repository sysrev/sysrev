(ns sysrev.main
  (:require [sysrev.base :refer [history-init init-state]]
            [sysrev.state.core :as s]
            [sysrev.ajax :as ajax]
            [sysrev.routes :as routes]
            [sysrev.ui.core :refer [main-content]]
            [reagent.core :as r]
            [cljs-time.core :as t]))

(defn ^:export run []
  (r/render
   [main-content]
   (js/document.getElementById "app")))

(defonce started
  (do (enable-console-print!)
      (init-state)
      (history-init)
      true))

(defonce note-sync-watcher
  (js/setInterval
   (fn []
     (when-let [user-id (s/current-user-id)]
       (let [notes (s/data [:notes :article])
             article-ids (and notes (keys notes))]
         (doseq [article-id article-ids]
           (doseq [[note-name {:keys [saved active update-time]}]
                   (s/data [:notes :article article-id user-id])]
             (when (not= saved active)
               (let [now ((t/default-ms-fn))]
                 (when (>= (- now update-time) 500)
                   (ajax/send-article-note article-id note-name active)))))))))
   750))

(run)
