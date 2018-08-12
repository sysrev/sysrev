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
            [sysrev.db.sources :as sources]
            [sysrev.import.pubmed :as pubmed]
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
  (let [{:keys [project-id] :as new-project} (project/create-project "Grault's
Corge")
        search-term "foo bar"
        ;; import articles to this project
        pmids (pubmed/get-all-pmids-for-query search-term)
        _ (pubmed/import-pmids-to-project-with-meta! pmids project-id
                                                     (sources/import-pmids-search-term-meta search-term
                                                                                            (count pmids)))
        _ (api/import-articles-from-search (:project-id new-project)
                                           search-term
                                           "PubMed")
        article-count (count (:pmids (pubmed/get-search-query-response search-term 1)))
        project-sources (sources/project-sources project-id)
        {:keys [source-id]}  (first (filter #(= (get-in % [:meta :search-term]) search-term) project-sources))
        source-article-ids (map :article-id (-> (select :article_id)
                                                (from :article_source)
                                                (where [:= :source_id source-id])
                                                do-query))]
    ;; The amount of PMIDs returned by the search term query is the same as
    ;; the total amount of articles in the project
    (is (= article-count
           (project/project-article-count project-id)))
    ;; ... and the amount of PMIDs is the same as the amount of articles in the project source
    (is (= article-count
           (count (-> (select :article_id)
                      (from :article_source)
                      (where [:= :source_id source-id])
                      do-query))))
    ;; .. check the article table as well
    (is (= article-count
           (count (-> (select :article_id)
                      (from :article)
                      (where [:in :article_id source-article-ids])
                      do-query))))
    ;; When the project is deleted, entries in the article / project_source tables are deleted
    ;; as well
    (sources/delete-project-source! source-id)
    (is (= 0
           (count (-> (select :article_id)
                      (from :article_source)
                      (where [:= :source_id source-id])
                      do-query))))
    (is (= 0
           (count (-> (select :article_id)
                      (from :article)
                      (where [:in :article_id source-article-ids])
                      do-query))))))
