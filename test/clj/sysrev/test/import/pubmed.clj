(ns sysrev.test.import.pubmed
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.test.core :refer
             [default-fixture database-rollback-fixture full-tests?]]
            [sysrev.pubmed :as pubmed]
            [sysrev.util :refer [parse-xml-str xml-find]]
            [sysrev.db.core :refer [*conn* active-db do-execute to-jsonb]]
            [sysrev.db.project :as project]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import]))

(use-fixtures :once default-fixture)
(use-fixtures :each database-rollback-fixture)

(def ss (partial clojure.string/join "\n"))

(def xml-vector-node
  (ss ["<doc>"
         "<test>"
           "<A>1</A>"
           "<A>2</A>"
           "<A>3</A>"
         "</test>"
       "</doc>"]))

(deftest find-xml-test
  (let [pxml (parse-xml-str xml-vector-node)
        els (xml-find pxml [:test :A])]
    (is (= (count els) 3))))

(deftest parse-pmid-test
  (let [parsed (pubmed/fetch-pmid-entry 11592337)]
    (is (str/starts-with? (:abstract parsed) "OBJECTIVE: To determine"))
    (is (str/includes? (:abstract parsed) "\n\nSAMPLE POPULATION: Corneal"))
    (is (= (str/join "; " (:authors parsed)) "Hendrix, DV.; Ward, DA.; Barnhill, MA."))
    (is (= (:public-id parsed) "11592337"))))

; Fails to get public-id for this pubmed article
(deftest parse-pmid-test-2
  (let [parsed (pubmed/fetch-pmid-entry 28280522)]
    (is (= (:public-id parsed) "28280522"))))

#_
(deftest test-importing-articles?
  (let [new-project (project/create-project "test project")
        new-project-id (:project-id new-project)
        non-existent-project-id 0000]
    (is (not (importing-articles? new-project-id)))
    ;; catch exception
    (is (= (str "No project with project-id: " non-existent-project-id))
        (try (importing-articles? non-existent-project-id)
             (catch Throwable e (.getMessage e))))
    ;; manually set the meta data 'importing-articles?' to true
    (-> (sqlh/update :project)
        (sset {:meta (to-jsonb (assoc-in {} [:importing-articles?] true))})
        (where [:= :project_id new-project-id])
        do-execute)
    (is (importing-articles? new-project-id))))

(deftest retrieve-articles
  (if (full-tests?)
    (log/info "running test (retrieve-articles)")
    (log/info "skipping test (retrieve-articles)"))
  (when (full-tests?)
    (let [result-count (fn [result] (-> result first :count))
          search-term "foo bar"
          pmids (:pmids (pubmed/get-search-query-response search-term 1))
          new-project (project/create-project "test project")
          new-project-id (:project-id new-project)]
      #_ (is (not (importing-articles? new-project-id)))
      (import/import-pubmed-search
       new-project-id {:search-term search-term}
       {:use-future? false :threads 1})
      #_ (is (not (importing-articles? new-project-id)))
    
      ;; Do we have the correct amount of PMIDS?
      (is (= (count pmids)
             (project/project-article-count new-project-id)))
      ;; is the author of a known article included in the results from get-pmids-summary?
      (is (= (-> (get-in (pubmed/get-pmids-summary pmids) [25706626])
                 :authors
                 first)
             {:name "Aung T", :authtype "Author", :clusterid ""}))
      ;; can all PMIDs for a search term with more than 100000 results be retrieved?
      (let [query "animals and cancer and blood"]
        (is (= (:count (pubmed/get-search-query-response query 1))
               (count (pubmed/get-all-pmids-for-query query))))))))
