(ns sysrev.test.import.pubmed
  (:require
   [clojure.test :refer :all]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [sysrev.test.core :refer [default-fixture database-rollback-fixture]]
   [sysrev.import.pubmed :refer [fetch-pmid-xml parse-pmid-xml import-pmids-to-project get-search-query-response get-pmids-summary get-all-pmids-for-query importing-articles?]]
   [sysrev.util :refer [parse-xml-str xml-find]]
   [sysrev.db.core :refer [*conn* active-db do-execute to-jsonb]]
   [sysrev.db.project :as project]))

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
  (let [xml (fetch-pmid-xml 11592337)
        parsed (parse-pmid-xml xml)]
    (is (str/starts-with? (:abstract parsed) "OBJECTIVE: To determine"))
    (is (str/includes? (:abstract parsed) "\n\nSAMPLE POPULATION: Corneal"))
    (is (= (str/join "; " (:authors parsed)) "Hendrix, DV.; Ward, DA.; Barnhill, MA."))
    (is (= (:public-id parsed) "11592337"))))

; Fails to get public-id for this pubmed article
(deftest parse-pmid-test-2
  (let [xml (fetch-pmid-xml 28280522)
        parsed (parse-pmid-xml xml)]
    (is (= (:public-id parsed) "28280522"))))

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
  (let [result-count (fn [result] (-> result first :count))
        pmids (:pmids (get-search-query-response "foo bar" 1))
        new-project (project/create-project "test project")
        new-project-id (:project-id new-project)
        article-summaries (get-pmids-summary pmids)]
    (is (not (importing-articles? new-project-id)))
    (import-pmids-to-project (:pmids (get-search-query-response "foo bar" 1)) new-project-id)
    (is (not (importing-articles? new-project-id)))
    ;; Do we have the correct amount of PMIDS?
    (is (= (count pmids)
           (project/project-article-count new-project-id)))
    ;; is the author of a known article included in the results from get-pmids-summary?
    (is (= (-> (get-in (get-pmids-summary pmids) [25706626])
               :authors
               first)
           {:name "Aung T", :authtype "Author", :clusterid ""}))
    ;; can all PMIDs for a search term with more than 100000 results be retrieved?
    (is (= (:count (get-search-query-response "animals and cancer and blood" 1))
           (count (get-all-pmids-for-query "animals and cancer and blood"))))))
