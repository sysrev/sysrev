(ns sysrev.source.interface-test
  (:require [clj-http.client :as http]
            [clojure-csv.core :as csv]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.logging :as log]
            [medley.core :as medley]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.export.core :as export]
            [sysrev.formats.endnote :refer [load-endnote-library-xml]]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.project.core :as project]
            [sysrev.source.interface :as src]
            [sysrev.test.core :as test :refer [completes?]]
            [sysrev.util :as util :refer [in?]]))

(deftest ^:integration import-pubmed-search
  (test/with-test-system [{:keys [sr-context]} {}]
    (util/with-print-time-elapsed "import-pubmed-search"
      (let [search-term "foo bar"
            pmids (:pmids (pubmed/get-search-query-response search-term 1))
            new-project (project/create-project "test-project")
            new-project-id (:project-id new-project)]
        (try
          (db/with-transaction
            (src/import-source
             sr-context :pubmed
             new-project-id {:search-term search-term}
             {:use-future? false}))
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

(deftest ^:integration import-endnote-xml
  (test/with-test-system [{:keys [sr-context]} {}]
    (util/with-print-time-elapsed "import-endnote-xml"
      (let [input (get-test-file "Sysrev_Articles_5505_20181128.xml")
            {:keys [project-id]} (project/create-project "autotest-endnote-import")]
        (try (is (= 0 (project/project-article-count project-id)))
             (is (completes? (src/import-source
                              sr-context :endnote-xml
                              project-id input {:use-future? false}))
                 (is (= 112 (project/project-article-count project-id))))
             (finally (project/delete-project project-id))))
      (let [filename "test2-endnote.xml.gz"
            gz-file (-> (str "test-files/" filename) io/resource io/file)
            {:keys [project-id]} (project/create-project "autotest-endnote-import-2")]
        (try (util/with-gunzip-file [file gz-file]
               (is (= 0 (project/project-article-count project-id)))
               (is (completes? (src/import-source
                                sr-context :endnote-xml
                                project-id {:file file :filename filename}
                                {:use-future? false})))
               (is (= 100 (project/project-article-count project-id)))
               (is (->> file io/reader load-endnote-library-xml
                        (map :primary-title)
                        (every? (every-pred string? not-empty)))))
             (finally (project/delete-project project-id)))))))

(deftest ^:integration import-pmid-file
  (test/with-test-system [{:keys [sr-context]} {}]
    (util/with-print-time-elapsed "import-pmid-file"
      (let [input (get-test-file "test-pmids-200.txt")
            {:keys [project-id]} (project/create-project "autotest-pmid-import")]
        (try
          (is (= 0 (project/project-article-count project-id)))
          (is (completes? (db/with-transaction
                            (src/import-source
                             sr-context :pmid-file
                             project-id input {:use-future? false}))))
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

(deftest ^:integration import-pdf-zip
  (test/with-test-system [{:keys [sr-context]} {}]
    (util/with-print-time-elapsed "import-pdf-zip"
      (let [filename "test-pdf-import.zip"
            file (-> (str "test-files/" filename) io/resource io/file)
            {:keys [project-id]} (project/create-project "autotest-pdf-zip-import")]
        (try
          (is (= 0 (project/project-article-count project-id)))
          (is (completes? (db/with-transaction
                            (src/import-source
                             sr-context :pdf-zip
                             project-id {:file file :filename filename}
                             {:use-future? false}))))
          (is (= 4 (project/project-article-count project-id)))
          (is (= 4 (project/project-article-pdf-count project-id)))
          (let [title-count #(q/find-count [:article :a] {:a.project-id project-id
                                                          :ad.title %}
                                           :join [[:article-data :ad] :a.article-data-id])]
            (is (= 1 (title-count "Sutinen Rosiglitazone.pdf")))
            (is (= 1 (title-count "Plosker Troglitazone.pdf"))))
          (finally (project/delete-project project-id)))))))

(deftest ^:integration import-ds-pubmed-titles
  (test/with-test-system [{:keys [sr-context]} {}]
    (util/with-print-time-elapsed "import-ds-pubmed-titles"
      (let [search-term "mouse rat computer six"
            {:keys [project-id]} (project/create-project "test-import-ds-pubmed-titles")]
        (try (db/with-transaction
               (src/import-source
                sr-context :pubmed
                project-id {:search-term search-term}
                {:use-future? false}))
             (let [adata (q/find [:article :a] {:a.project-id project-id}
                                 :ad.*, :join [[:article-data :ad] :a.article-data-id])
                   db-titles (->> adata
                                  (util/index-by #(-> % :external-id util/parse-integer))
                                  (medley/map-vals :title))
                   ds-titles (->> (ds-api/fetch-pubmed-articles
                                   (keys db-titles) :fields [:primary-title])
                                  (medley/map-vals :primary-title))]
               (log/infof "loaded %d pubmed titles" (count db-titles))
               (is (= (count db-titles) (count ds-titles)))
               (is (= db-titles ds-titles)))
             (finally (project/delete-project project-id)))))))
