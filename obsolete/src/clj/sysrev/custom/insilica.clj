(ns sysrev.custom.insilica
  (:require [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.article.core :as article]
            [sysrev.label.core :as labels]
            [sysrev.project.article-list :as article-list]
            [sysrev.project.core :as project]
            [sysrev.project.clone :as clone]
            [sysrev.util :refer [in? parse-integer]]))

(defn article-label-value-present? [article-labels label-id value]
  (some (fn [{:keys [answer]}]
          (or (= answer value)
              (and (sequential? answer)
                   (in? answer value))))
        (get-in article-labels [:labels label-id])))

(defn prostate-clinical-trial? [article-labels overall-id clinical-id preclinical-id]
  (let [included? (article-list/article-included? article-labels overall-id true)
        clinical? (article-label-value-present?
                   article-labels clinical-id true)
        not-clinical? (article-label-value-present?
                       article-labels clinical-id false)
        preclinical? (article-label-value-present?
                      article-labels preclinical-id true)]
    (and included?
         (or clinical?
             (and (not not-clinical?)
                  (not preclinical?))))))

(defn prostate-preclinical? [article-labels overall-id clinical-id preclinical-id]
  (let [included? (article-list/article-included? article-labels overall-id true)
        clinical? (article-label-value-present?
                   article-labels clinical-id true)
        preclinical? (article-label-value-present?
                      article-labels preclinical-id true)
        not-preclinical? (article-label-value-present?
                          article-labels preclinical-id false)]
    (and included?
         (or preclinical?
             (and (not not-preclinical?)
                  (not clinical?))))))

(defn prostate-clinical-article-uuids [project-id]
  (let [public-labels (labels/query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)
        clinical-id    (q/find-label-1 {:project-id project-id
                                        :short-label "Clinical Trial"} :label-id)
        preclinical-id (q/find-label-1 {:project-id project-id
                                        :short-label "Preclinical"} :label-id)]
    (->> (keys public-labels)
         (filter (fn [article-id]
                   (let [article-labels (get public-labels article-id)]
                     (prostate-clinical-trial?
                      article-labels overall-id clinical-id preclinical-id))))
         (article/article-ids-to-uuids))))

(defn prostate-preclinical-article-uuids [project-id]
  (let [public-labels (labels/query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)
        clinical-id    (q/find-label-1 {:project-id project-id
                                        :short-label "Clinical Trial"} :label-id)
        preclinical-id (q/find-label-1 {:project-id project-id
                                        :short-label "Preclinical"} :label-id)]
    (->> (keys public-labels)
         (filter (fn [article-id]
                   (let [article-labels (get public-labels article-id)]
                     (prostate-preclinical?
                      article-labels overall-id clinical-id preclinical-id))))
         (article/article-ids-to-uuids))))

(defn clone-prostate-clinical [project-id]
  (let [title (q/get-project project-id :name, :include-disabled true)]
    (clone/clone-subproject-articles
     (str title " - Clinical")
     project-id
     (prostate-clinical-article-uuids project-id)
     :labels? true :answers? true)))

(defn clone-prostate-preclinical [project-id]
  (let [title (q/get-project project-id :name, :include-disabled true)]
    (clone/clone-subproject-articles
     (str title " - Preclinical")
     project-id
     (prostate-preclinical-article-uuids project-id)
     :labels? true :answers? true)))

(defn pubmed-foreign-article-ids [project-id]
  (->> (q/find-article {:a.project-id project-id}
                       [:a.article-id :a.primary-title :a.public-id]
                       :include-disabled-source true)
       (filter (fn [{:keys [public-id primary-title]}]
                 (and (parse-integer public-id)
                      (re-matches #"^ *\[.*\][ \.]*$" primary-title))))
       (mapv :article-id)))

(defn disable-pubmed-foreign-articles [project-id]
  (let [article-ids (pubmed-foreign-article-ids project-id)]
    (db/with-transaction
      (doseq [article-id article-ids]
        (article/set-article-flag
         article-id "pubmed foreign language" true)))))
