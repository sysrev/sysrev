(ns sysrev.predict.core
  (:require
   [sysrev.shared.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute with-transaction sql-now clear-project-cache]]
   [sysrev.db.project :refer [project-overall-label-id]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.db.queries :as q]))

(defn create-predict-version
  "Adds a new predict-version entry to database."
  [note]
  (-> (insert-into :predict-version)
      (values [{:note note}])
      do-execute))

(defn create-predict-run
  "Adds a new predict-run entry to the database, and returns the entry."
  [project-id predict-version-id]
  (try
    (with-transaction
      (-> (insert-into :predict-run)
          (values [{:project-id project-id
                    :predict-version-id predict-version-id}])
          (returning :predict-run-id)
          do-query first :predict-run-id))
    (finally
      (clear-project-cache project-id))))

(defn store-article-predictions
  [project-id predict-run-id label-id article-values]
  (let [sql-entries (->> article-values
                         (mapv
                          (fn [{:keys [article-id value]}]
                            {:predict-run-id predict-run-id
                             :article-id article-id
                             :label-id label-id
                             :stage 1
                             :val value})))]
    (try
      (with-transaction
        (doseq [sql-group (->> sql-entries (partition-all 500))]
          (-> (insert-into :label-predicts)
              (values (vec sql-group))
              do-execute)))
      (finally
        (clear-project-cache project-id)))))
