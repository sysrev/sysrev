(ns sysrev.test.import
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.test.core :as test :refer [completes?]]
            [sysrev.pubmed :as pubmed]
            [sysrev.db.project :as project]
            [sysrev.source.import :as import]
            [sysrev.util :as u :refer [parse-xml-str xml-find]]
            [clj-http.client :as http]))

(use-fixtures :once test/default-fixture)
(use-fixtures :each test/database-rollback-fixture)

(def ss (partial clojure.string/join "\n"))

(def xml-vector-node
  (ss ["<doc>"
         "<test>"
           "<A>1</A>"
           "<A>2</A>"
           "<A>3</A>"
         "</test>"
       "</doc>"]))

(deftest parse-xml
  (let [pxml (parse-xml-str xml-vector-node)
        els (xml-find pxml [:test :A])]
    (is (= (count els) 3))))

(deftest pubmed-article-parse
  (let [[parsed] (pubmed/fetch-pmid-entries-cassandra [11592337])]
    (is (str/starts-with? (:abstract parsed) "OBJECTIVE: To determine"))
    (is (str/includes? (:abstract parsed) "\n\nSAMPLE POPULATION: Corneal"))
    (is (= (str/join "; " (:authors parsed)) "Hendrix, DV.; Ward, DA.; Barnhill, MA."))
    (is (= (:public-id parsed) "11592337"))))

(deftest pubmed-article-public-id
  (let [[parsed] (pubmed/fetch-pmid-entries-cassandra [28280522])]
    (is (= (:public-id parsed) "28280522"))))

(deftest import-pubmed-search
  (if (test/full-tests?)
    (log/info "running test (import-pubmed-search)")
    (log/info "skipping test (import-pubmed-search)"))
  (when (test/full-tests?)
    (let [result-count (fn [result] (-> result first :count))
          search-term "foo bar"
          pmids (:pmids (pubmed/get-search-query-response search-term 1))
          new-project (project/create-project "test project")
          new-project-id (:project-id new-project)]
      (try
        (import/import-pubmed-search
         new-project-id {:search-term search-term}
         {:use-future? false :threads 1})
        ;; Do we have the correct amount of PMIDS?
        (is (= (count pmids) (project/project-article-count new-project-id)))
        ;; is the author of a known article included in the results from get-pmids-summary?
        (is (= (-> (pubmed/get-pmids-summary pmids)
                   (get 25706626) :authors first)
               {:name "Aung T", :authtype "Author", :clusterid ""}))
        ;; can all PMIDs for a search term with more than 100000 results be retrieved?
        (let [query "animals and cancer and blood"]
          (is (= (:count (pubmed/get-search-query-response query 1))
                 (count (pubmed/get-all-pmids-for-query query)))))
        (finally
          (project/delete-project new-project-id))))))

(defn get-test-file [fname]
  (let [url (str "https://s3.amazonaws.com/sysrev-test-files/" fname)
        tempfile (u/create-tempfile)]
    (spit tempfile (-> url http/get :body))
    {:filename fname, :file tempfile}))

(deftest import-endnote-xml
  (let [{:keys [file filename] :as input} (get-test-file "Sysrev_Articles_5505_20181128.xml")
        {:keys [project-id]} (project/create-project "autotest endnote import")]
    (try
      (is (= 0 (project/project-article-count project-id)))
      (is (completes? (import/import-endnote-xml
                       project-id input {:use-future? false})))
      (is (= 112 (project/project-article-count project-id)))
      (finally
        (project/delete-project project-id)))))

(deftest import-pmid-file
  (let [{:keys [file filename] :as input} (get-test-file "test-pmids-200.txt")
        {:keys [project-id]} (project/create-project "autotest pmid import")]
    (try
      (is (= 0 (project/project-article-count project-id)))
      (is (completes? (import/import-pmid-file
                       project-id input {:use-future? false})))
      (is (= 200 (project/project-article-count project-id)))
      (finally
        (project/delete-project project-id)))))
