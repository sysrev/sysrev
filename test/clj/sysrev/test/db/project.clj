(ns sysrev.test.db.project
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [honeysql.helpers :refer [select from where merge-where]]
            [sysrev.db.core :as db :refer [do-query sql-field]]
            [sysrev.db.queries :as q]
            [sysrev.test.core :refer [default-fixture]]))

(use-fixtures :once default-fixture)

(defn- filter-article-by-disable-flag
  [m disabled? & [{:keys [tname] :or {tname :a}} :as _opts]]
  (let [exists
        [:exists
         (-> (select :*)
             (from [:article-flag :af-filter])
             (where [:and
                     [:= :af-filter.disable true]
                     [:= :af-filter.article-id
                      (sql-field tname :article-id)]]))]]
    (cond-> m
      disabled? (merge-where exists)
      (not disabled?) (merge-where [:not exists]))))

(deftest article-flag-counts
  (doseq [project-id (q/find :project {} :project-id, :limit 10)]
    (let [query (q/select-project-articles
                 project-id [:%count.*] {:include-disabled? true})
          get-count #(-> % do-query first :count)
          [total flag-enabled flag-disabled]
          (db/with-transaction
            [(get-count query)
             (get-count (-> query (filter-article-by-disable-flag true)))
             (get-count (-> query (filter-article-by-disable-flag false)))])]
      (is (= total (+ flag-enabled flag-disabled))))))

#_
(deftest project-sources-creation-deletion
  ;; Create Project
  (let [{:keys [project-id]} (project/create-project "Grault's Corge")
        search-term "foo bar"
        ;; import articles to this project
        _ (pubmed/get-all-pmids-for-query search-term)
        _ (import/import-pubmed-search project-id {:search-term search-term}
                                       {:use-future? false :threads 1})
        article-count (count (:pmids (pubmed/get-search-query-response search-term 1)))
        project-sources (source/project-sources project-id)
        {:keys [source-id]} (->> project-sources
                                 (filter #(= (get-in % [:meta :search-term]) search-term))
                                 first)
        source-article-ids (q/find :article-source {:source-id source-id} :article-id)]
    ;; The amount of PMIDs returned by the search term query is the same as
    ;; the total amount of articles in the project
    (is (= article-count (project/project-article-count project-id)))
    ;; ... and the amount of PMIDs is the same as the amount of articles in the project source
    (is (= article-count (q/find-count :article-source {:source-id source-id})))
    ;; .. check the article table as well
    (is (= article-count (q/find-count :article {:article-id source-article-ids})))
    ;; When the project is deleted, entries in the article / project-source tables are deleted
    ;; as well
    (source/delete-source source-id)
    (is (= 0 (q/find-count :article-source {:source-id source-id})))
    (is (= 0 (q/find-count :article {:article-id source-article-ids})))
    (project/delete-project project-id)))
