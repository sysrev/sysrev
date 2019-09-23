(ns sysrev.test.import
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [clojure-csv.core :as csv]
            [clojure.java.io :as io]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.test.core :as test :refer [completes?]]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.export.core :as export]
            [sysrev.source.import :as import]
            [sysrev.formats.endnote :refer [load-endnote-library-xml]]
            [sysrev.util :as util :refer [parse-xml-str xml-find]]
            [sysrev.shared.util :as sutil :refer [in?]]))

(use-fixtures :once test/default-fixture)
(use-fixtures :each test/database-rollback-fixture)

(def ss (partial str/join "\n"))

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

#_
(deftest pubmed-article-parse
  (let [[parsed] (pubmed/fetch-pmid-entries-cassandra [11592337])]
    (is (str/starts-with? (:abstract parsed) "OBJECTIVE: To determine"))
    (is (str/includes? (:abstract parsed) "\n\nSAMPLE POPULATION: Corneal"))
    (is (= (str/join "; " (:authors parsed)) "Hendrix, DV.; Ward, DA.; Barnhill, MA."))
    (is (= (:public-id parsed) "11592337"))))

#_
(deftest pubmed-article-public-id
  (let [[parsed] (pubmed/fetch-pmid-entries-cassandra [28280522])]
    (is (= (:public-id parsed) "28280522"))))

(deftest import-pubmed-search
  (when (and (test/full-tests?) (not (test/remote-test?)))
    (util/with-print-time-elapsed "import-pubmed-search"
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
          (finally (project/delete-project new-project-id)))))))

(defn get-test-file [fname]
  (let [url (str "https://s3.amazonaws.com/sysrev-test-files/" fname)
        tempfile (util/create-tempfile)]
    (spit tempfile (-> url http/get :body))
    {:filename fname, :file tempfile}))

(deftest import-endnote-xml
  (when (not (test/remote-test?))
    (util/with-print-time-elapsed "import-endnote-xml"
      (let [{:keys [file filename] :as input} (get-test-file "Sysrev_Articles_5505_20181128.xml")
            {:keys [project-id]} (project/create-project "autotest endnote import")]
        (try (is (= 0 (project/project-article-count project-id)))
             (is (completes? (import/import-endnote-xml
                              project-id input {:use-future? false})))
             (is (= 112 (project/project-article-count project-id)))
             (finally (project/delete-project project-id))))
      (let [filename "test2-endnote.xml.gz"
            gz-file (-> (str "test-files/" filename) io/resource io/file)
            {:keys [project-id]} (project/create-project "autotest endnote import 2")]
        (try (util/with-gunzip-file [file gz-file]
               (is (= 0 (project/project-article-count project-id)))
               (is (completes? (import/import-endnote-xml
                                project-id {:file file :filename filename}
                                {:use-future? false})))
               (is (= 100 (project/project-article-count project-id)))
               (is (->> file io/reader load-endnote-library-xml
                        (map :primary-title)
                        (every? (every-pred string? not-empty)))))
             (finally (project/delete-project project-id)))))))

(deftest import-pmid-file
  (when (not (test/remote-test?))
    (util/with-print-time-elapsed "import-pmid-file"
      (let [{:keys [file filename] :as input} (get-test-file "test-pmids-200.txt")
            {:keys [project-id]} (project/create-project "autotest pmid import")]
        (try
          (is (= 0 (project/project-article-count project-id)))
          (is (completes? (import/import-pmid-file
                           project-id input {:use-future? false})))
          (is (= 200 (project/project-article-count project-id)))
          (log/info "checking articles-csv export")
          (let [articles-csv (rest (export/export-articles-csv project-id))
                article-ids (set (project/project-article-ids project-id))]
            (is (= (count articles-csv) (count article-ids)))
            (is (every? (fn [article-id]
                          (some #(in? % (str article-id)) articles-csv))
                        article-ids))
            (is (= articles-csv (-> (csv/write-csv articles-csv)
                                    (csv/parse-csv :strict true)))))
          (finally (project/delete-project project-id)))))))

(deftest import-pdf-zip
  (when (not (test/remote-test?))
    (util/with-print-time-elapsed "import-pdf-zip"
      (let [filename "test-pdf-import.zip"
            file (-> (str "test-files/" filename) io/resource io/file)
            {:keys [project-id]} (project/create-project "autotest pdf-zip import")]
        (try
          (is (= 0 (project/project-article-count project-id)))
          (is (completes? (import/import-pdf-zip
                           project-id {:file file :filename filename}
                           {:use-future? false})))
          (is (= 4 (project/project-article-count project-id)))
          (is (= 4 (project/project-article-pdf-count project-id)))
          (let [title-count #(q/find-count [:article :a] {:ad.title %}
                                           :join [:article-data:ad :a.article-data-id])]
            (is (= 1 (title-count "Sutinen Rosiglitazone.pdf")))
            (is (= 1 (title-count "Plosker Troglitazone.pdf"))))
          (finally (project/delete-project project-id)))))))
