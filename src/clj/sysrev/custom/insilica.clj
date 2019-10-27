(ns sysrev.custom.insilica
  #_ (:require [sysrev.db.core :refer [do-query with-transaction]]
               [sysrev.article.core :as article]
               [sysrev.label.core :as labels]
               [sysrev.project.core :as project]
               [sysrev.db.queries :as q]
               [sysrev.project.clone :as clone]
               [sysrev.shared.util :refer [in? parse-integer]]))

#_
(defn article-label-value-present? [article-labels label-id value]
  (some (fn [{:keys [answer]}]
          (or (= answer value)
              (and (sequential? answer)
                   (in? answer value))))
        (get-in article-labels [:labels label-id])))

#_
(defn prostate-clinical-trial? [article-labels overall-id clinical-id preclinical-id]
  (let [included? (article-list/article-included? article-labels overall-id true)
        clinical? (article-label-value-present?
                   article-labels clinical-id true)
        not-clinical? (article-label-value-present?
                       article-labels clinical-id false)
        preclinical? (article-label-value-present?
                      article-labels preclinical-id true)
        not-preclinical? (article-label-value-present?
                          article-labels preclinical-id false)]
    (and included?
         (or clinical?
             (and (not not-clinical?)
                  (not preclinical?))))))

#_
(defn prostate-preclinical? [article-labels overall-id clinical-id preclinical-id]
  (let [included? (article-list/article-included? article-labels overall-id true)
        clinical? (article-label-value-present?
                   article-labels clinical-id true)
        not-clinical? (article-label-value-present?
                       article-labels clinical-id false)
        preclinical? (article-label-value-present?
                      article-labels preclinical-id true)
        not-preclinical? (article-label-value-present?
                          article-labels preclinical-id false)]
    (and included?
         (or preclinical?
             (and (not not-preclinical?)
                  (not clinical?))))))

#_
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
         (article/article-ids-to-uuids))))

#_
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
         (article/article-ids-to-uuids))))

#_
(defn clone-prostate-clinical [project-id]
  (let [title (:name (q/query-project-by-id project-id [:name]))]
    (clone/clone-subproject-articles
     (str title " - Clinical")
     project-id
     (prostate-clinical-article-uuids project-id)
     :labels? true :answers? true)))

#_
(defn clone-prostate-preclinical [project-id]
  (let [title (:name (q/query-project-by-id project-id [:name]))]
    (clone/clone-subproject-articles
     (str title " - Preclinical")
     project-id
     (prostate-preclinical-article-uuids project-id)
     :labels? true :answers? true)))

#_
(defn pubmed-foreign-article-ids [project-id]
  (-> (q/select-project-articles
       project-id [:a.article-id :a.primary-title :a.public-id]
       {:include-disabled-source? true})
      (->> do-query
           (filter
            (fn [{:keys [public-id primary-title]}]
              (and (parse-integer public-id)
                   (re-matches #"^ *\[.*\][ \.]*$" primary-title))))
           (mapv :article-id))))

#_
(defn disable-pubmed-foreign-articles [project-id]
  (let [article-ids (pubmed-foreign-article-ids project-id)]
    (with-transaction
      (doseq [article-id article-ids]
        (article/set-article-flag
         article-id "pubmed foreign language" true)))))
