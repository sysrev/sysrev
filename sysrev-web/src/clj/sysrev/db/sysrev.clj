(ns sysrev.db.sysrev
  (:require
   [sysrev.db.core :refer [do-query do-execute do-transaction]]
   [sysrev.db.articles :as articles]
   [sysrev.db.users :as users]
   [sysrev.util :refer [mapify-by-id mapify-group-by-id]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]))

;; TODO: use a project_id to support multiple systematic review projects

(defn all-overall-labels []
  (-> (select :a.article_id :ac.user_id :ac.answer)
      (from [:article :a])
      (join [:article_criteria :ac]
            [:= :ac.article_id :a.article_id])
      (merge-join [:criteria :c]
                  [:= :ac.criteria_id :c.criteria_id])
      (where [:and
              [:= :c.name "overall include"]
              [:or
               [:= :ac.answer true]
               [:= :ac.answer false]]])
      do-query))

(defn get-project-stats []
  (let [n-articles
        (-> (select :%count.*)
            (from :article)
            (where true)
            do-query first :count)
        labeled-counts (all-overall-labels)]
    {:article-count n-articles
     :labeled-counts (count labeled-counts)}))
