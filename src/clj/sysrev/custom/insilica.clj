(ns sysrev.custom.insilica
  (:require [sysrev.db.core :refer [do-query]]
            [sysrev.db.articles :as articles]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.db.queries :as q]
            [sysrev.clone-project :as clone]
            [sysrev.shared.util :refer [in?]]
            [sysrev.shared.article-list :as article-list]))

(defn prostate-clinical-trial? [article-labels overall-id clinical-id preclinical-id]
  (let [included? (article-list/article-included? article-labels overall-id true)
        clinical? (article-list/article-label-value-present?
                   article-labels clinical-id true)
        not-clinical? (article-list/article-label-value-present?
                       article-labels clinical-id false)
        preclinical? (article-list/article-label-value-present?
                      article-labels preclinical-id true)
        not-preclinical? (article-list/article-label-value-present?
                          article-labels preclinical-id false)]
    (and included?
         (or clinical?
             (and (not not-clinical?)
                  (not preclinical?))))))

(defn prostate-preclinical? [article-labels overall-id clinical-id preclinical-id]
  (let [included? (article-list/article-included? article-labels overall-id true)
        clinical? (article-list/article-label-value-present?
                   article-labels clinical-id true)
        not-clinical? (article-list/article-label-value-present?
                       article-labels clinical-id false)
        preclinical? (article-list/article-label-value-present?
                      article-labels preclinical-id true)
        not-preclinical? (article-list/article-label-value-present?
                          article-labels preclinical-id false)]
    (and included?
         (or preclinical?
             (and (not not-preclinical?)
                  (not clinical?))))))

(defn prostate-clinical-article-uuids [project-id]
  (let [public-labels (labels/query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)
        clinical-id
        (:label-id
         (q/query-label-where
          project-id [:= :short-label "Clinical Trial"] [:label-id]))
        preclinical-id
        (:label-id
         (q/query-label-where
          project-id [:= :short-label "Preclinical"] [:label-id]))]
    (->> (keys public-labels)
         (filter (fn [article-id]
                   (let [article-labels (get public-labels article-id)]
                     (prostate-clinical-trial?
                      article-labels overall-id clinical-id preclinical-id))))
         (articles/article-ids-to-uuids))))

(defn prostate-preclinical-article-uuids [project-id]
  (let [public-labels (labels/query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)
        clinical-id
        (:label-id
         (q/query-label-where
          project-id [:= :short-label "Clinical Trial"] [:label-id]))
        preclinical-id
        (:label-id
         (q/query-label-where
          project-id [:= :short-label "Preclinical"] [:label-id]))]
    (->> (keys public-labels)
         (filter (fn [article-id]
                   (let [article-labels (get public-labels article-id)]
                     (prostate-preclinical?
                      article-labels overall-id clinical-id preclinical-id))))
         (articles/article-ids-to-uuids))))

(defn clone-prostate-clinical [project-id]
  (let [title (:name (q/query-project-by-id project-id [:name]))]
    (clone/clone-subproject-articles
     (str title " - Clinical")
     project-id
     (prostate-clinical-article-uuids project-id)
     :labels? true :answers? true)))

(defn clone-prostate-preclinical [project-id]
  (let [title (:name (q/query-project-by-id project-id [:name]))]
    (clone/clone-subproject-articles
     (str title " - Preclinical")
     project-id
     (prostate-preclinical-article-uuids project-id)
     :labels? true :answers? true)))
