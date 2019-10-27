(ns sysrev.predict.core
  (:require [sysrev.db.core :as db]
            [sysrev.db.queries :as q]))

(defn ^:repl create-predict-version [note]
  (q/create :predict-version {:note note}))

(defn create-predict-run
  "Adds a new predict-run entry to the database, and returns the entry."
  [project-id predict-version-id]
  (db/with-clear-project-cache project-id
    (q/create :predict-run {:project-id project-id
                            :predict-version-id predict-version-id}
              :returning :predict-run-id)))

(defn store-article-predictions
  [project-id predict-run-id label-id article-values]
  (db/with-clear-project-cache project-id
    (doseq [entries-group (->> (for [{:keys [article-id value]} article-values]
                                 {:predict-run-id predict-run-id
                                  :article-id article-id
                                  :label-id label-id
                                  :stage 1
                                  :val value})
                               (partition-all 500))]
      (q/create :label-predicts entries-group))))
