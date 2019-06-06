(ns sysrev.test.db.project
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.api :as api]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import]
            [sysrev.pubmed :as pubmed]
            [sysrev.test.core :refer [default-fixture database-rollback-fixture completes?]]
            [sysrev.test.db.core :refer [test-project-ids]]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(deftest article-flag-counts
  (doseq [project-id (take 10 (test-project-ids))]
    (let [query (q/select-project-articles
                 project-id [:%count.*] {:include-disabled? true})
          total (-> query do-query first :count)
          flag-enabled (-> query (q/filter-article-by-disable-flag true)
                           do-query first :count)
          flag-disabled (-> query (q/filter-article-by-disable-flag false)
                            do-query first :count)]
      (is (= total (+ flag-enabled flag-disabled))))))

(deftest project-sources-creation-deletion
  ;; Create Project
  (let [{:keys [project-id] :as new-project} (project/create-project "Grault's Corge")
        search-term "foo bar"
        ;; import articles to this project
        pmids (pubmed/get-all-pmids-for-query search-term)
        _ (import/import-pubmed-search
           project-id {:search-term search-term}
           {:use-future? false :threads 1})
        article-count (count (:pmids (pubmed/get-search-query-response search-term 1)))
        project-sources (source/project-sources project-id)
        {:keys [source-id]}
        (->> project-sources
             (filter #(= (get-in % [:meta :search-term]) search-term))
             first)
        source-article-ids (map :article-id (-> (select :article-id)
                                                (from :article-source)
                                                (where [:= :source-id source-id])
                                                do-query))]
    ;; The amount of PMIDs returned by the search term query is the same as
    ;; the total amount of articles in the project
    (is (= article-count
           (project/project-article-count project-id)))
    ;; ... and the amount of PMIDs is the same as the amount of articles in the project source
    (is (= article-count
           (count (-> (select :article-id)
                      (from :article-source)
                      (where [:= :source-id source-id])
                      do-query))))
    ;; .. check the article table as well
    (is (= article-count
           (count (-> (select :article-id)
                      (from :article)
                      (where [:in :article-id source-article-ids])
                      do-query))))
    ;; When the project is deleted, entries in the article / project-source tables are deleted
    ;; as well
    (source/delete-source source-id)
    (is (= 0 (count (-> (select :article-id)
                        (from :article-source)
                        (where [:= :source-id source-id])
                        do-query))))
    (is (= 0 (count (-> (select :article-id)
                        (from :article)
                        (where [:in :article-id source-article-ids])
                        do-query))))))
