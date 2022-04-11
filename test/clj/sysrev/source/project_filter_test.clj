(ns sysrev.source.project-filter-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [medley.core :as medley]
            [sysrev.api :as api]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.scheduler.living-data-sources :as living-data-sources]
            [sysrev.shared.ctgov :as ctgov]
            [sysrev.source.core :as source]
            [sysrev.source.interface :as src]
            [sysrev.test.core :as test]
            [sysrev.user.interface :as user]
            [venia.core :as venia]))

(deftest ^:integration test-import-one-type
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [{:keys [api-token user-id]} (test/create-test-user system)]
      (user/change-user-setting user-id :dev-account-enabled? true)
      (testing "Articles can be imported from a project with one type of article"
        (let [project-1-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Source"
                                user-id
                                true)
                               :project
                               :project-id)
              project-1-url (str "https://sysrev.com/p/" project-1-id)
              project-2-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Target"
                                user-id
                                true)
                               :project
                               :project-id)
              filename "test-pdf-import.zip"
              pdf-zip (-> (str "test-files/" filename) io/resource io/file)]
          (db/with-transaction
            (src/import-source
             sr-context :pdf-zip
             project-1-id {:file pdf-zip :filename filename}
             {:use-future? false}))
          (is (= 4 (project/project-article-count project-1-id)))
          (is (= {:data {:importArticleFilterUrl true}}
                 (->> (test/graphql-request
                       system
                       (venia/graphql-query
                        {:venia/operation {:operation/type :mutation
                                           :operation/name "M"}
                         :venia/queries [[:importArticleFilterUrl
                                          {:sourceID project-1-id
                                           :targetID project-2-id
                                           :url (str project-1-url "/articles")}]]})
                       :api-key api-token))))
          (is (test/wait-not-importing? system project-2-id))
          (is (= 4 (project/project-article-count project-2-id)))
          (let [title-count #(q/find-count [:article :a] {:a.project-id project-2-id
                                                          :ad.title %}
                                           :join [[:article-data :ad] :a.article-data-id])]
            (is (= 1 (title-count "Sutinen Rosiglitazone.pdf")))
            (is (= 1 (title-count "Plosker Troglitazone.pdf")))))))))

(deftest ^:integration test-import-one-type-with-filters
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [{:keys [api-token user-id]} (test/create-test-user system)]
      (user/change-user-setting user-id :dev-account-enabled? true)
      (testing "Articles can be imported from a project with one type of article and filters"
        (let [project-1-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Source"
                                user-id
                                true)
                               :project
                               :project-id)
              project-1-url (str "https://sysrev.com/p/" project-1-id)
              project-2-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Target"
                                user-id
                                true)
                               :project
                               :project-id)
              filename "test-pdf-import.zip"
              pdf-zip (-> (str "test-files/" filename) io/resource io/file)]
          (db/with-transaction
            (src/import-source
             sr-context :pdf-zip
             project-1-id {:file pdf-zip :filename filename}
             {:use-future? false}))
          (is (= 4 (project/project-article-count project-1-id)))
          (is (= {:data {:importArticleFilterUrl true}}
                 (->> (test/graphql-request
                       system
                       (venia/graphql-query
                        {:venia/operation {:operation/type :mutation
                                           :operation/name "M"}
                         :venia/queries [[:importArticleFilterUrl
                                          {:sourceID project-1-id
                                           :targetID project-2-id
                                           :url (str project-1-url "/articles?text-search=sutinen")}]]})
                       :api-key api-token))))
          (is (test/wait-not-importing? system project-2-id))
          (is (= 1 (project/project-article-count project-2-id)))
          (let [title-count #(q/find-count [:article :a] {:a.project-id project-2-id
                                                          :ad.title %}
                                           :join [[:article-data :ad] :a.article-data-id])]
            (is (= 1 (title-count "Sutinen Rosiglitazone.pdf")))
            (is (= 0 (title-count "Plosker Troglitazone.pdf")))))))))

(deftest ^:integration test-import-two-types
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [{:keys [api-token user-id]} (test/create-test-user system)]
      (user/change-user-setting user-id :dev-account-enabled? true)
      (testing "Articles can be imported from a project with two types of article"
        (let [project-1-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Source"
                                user-id
                                true)
                               :project
                               :project-id)
              project-1-url (str "https://sysrev.com/p/" project-1-id)
              project-2-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Target"
                                user-id
                                true)
                               :project
                               :project-id)
              filename "test-pdf-import.zip"
              pdf-zip (-> (str "test-files/" filename) io/resource io/file)]
          (db/with-transaction
            (src/import-source
             sr-context :pdf-zip
             project-1-id {:file pdf-zip :filename filename}
             {:use-future? false})
            (let [query (ctgov/query->datapub-input {:search "cancer"})
                  entity-ids (->> (dpc/search-dataset query "externalId id" :endpoint (:datapub-ws (:config system)))
                                  (map :id))]
              (src/import-source
               sr-context :ctgov
               project-1-id
               {:entity-ids entity-ids
                :query query}
               {:use-future? false})))
          (is (= 7 (project/project-article-count project-1-id)))
          (is (= {:data {:importArticleFilterUrl true}}
                 (->> (test/graphql-request
                       system
                       (venia/graphql-query
                        {:venia/operation {:operation/type :mutation
                                           :operation/name "M"}
                         :venia/queries [[:importArticleFilterUrl
                                          {:sourceID project-1-id
                                           :targetID project-2-id
                                           :url (str project-1-url "/articles")}]]})
                       :api-key api-token))))
          (is (test/wait-not-importing? system project-2-id))
          (is (= 7 (project/project-article-count project-2-id)))
          (let [title-count #(q/find-count [:article :a] {:a.project-id project-2-id
                                                          :ad.title %}
                                           :join [[:article-data :ad] :a.article-data-id])]
            (is (= 1 (title-count "Plosker Troglitazone.pdf")))
            (is (= 1 (title-count "A Study of TAS2940 in Participants With Locally Advanced or Metastatic Solid Tumor Cancer")))))))))

(deftest ^:integration test-re-import-one-type
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [{:keys [api-token user-id]} (test/create-test-user system)]
      (user/change-user-setting user-id :dev-account-enabled? true)
      (testing "Articles can be re-imported from a project with one type of article"
        (let [project-1-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Source"
                                user-id
                                true)
                               :project
                               :project-id)
              project-1-url (str "https://sysrev.com/p/" project-1-id)
              project-2-id (-> (api/create-project-for-user!
                                sr-context
                                "Project Filter Import Target"
                                user-id
                                true)
                               :project
                               :project-id)
              filename "test-pdf-import.zip"
              pdf-zip (-> (str "test-files/" filename) io/resource io/file)]
          (is (= {:data {:importArticleFilterUrl true}}
                 (->> (test/graphql-request
                       system
                       (venia/graphql-query
                        {:venia/operation {:operation/type :mutation
                                           :operation/name "M"}
                         :venia/queries [[:importArticleFilterUrl
                                          {:sourceID project-1-id
                                           :targetID project-2-id
                                           :url (str project-1-url "/articles")}]]})
                       :api-key api-token))))
          (is (test/wait-not-importing? system project-2-id))
          (is (= 0 (project/project-article-count project-2-id)))
          (let [title-count #(q/find-count [:article :a] {:a.project-id project-2-id
                                                          :ad.title %}
                                           :join [[:article-data :ad] :a.article-data-id])]
            (is (= 0 (title-count "Sutinen Rosiglitazone.pdf")))
            (is (= 0 (title-count "Plosker Troglitazone.pdf"))))
          (db/with-transaction
            (src/import-source
             sr-context :pdf-zip project-1-id {:file pdf-zip :filename filename}
             {:use-future? false}))
          (is (= 4 (project/project-article-count project-1-id)))
          (let [[{:keys [source-id] :as source}] (source/project-sources project-2-id)]
            (is (= 4 (living-data-sources/check-new-articles-project-filter source)))
            (is (= {:source-id source-id}
                   (source/re-import {:sr-context sr-context} project-2-id source)))
            (is (test/wait-not-importing? system project-2-id 10000))
            (is (= 4 (project/project-article-count project-2-id)))
            (let [title-count #(q/find-count [:article :a] {:a.project-id project-2-id
                                                            :ad.title %}
                                             :join [[:article-data :ad] :a.article-data-id])]
              (is (= 1 (title-count "Sutinen Rosiglitazone.pdf")))
              (is (= 1 (title-count "Plosker Troglitazone.pdf"))))
            (testing "Re-importing does not create duplicate articles"
              (is (= 0 (living-data-sources/check-new-articles-project-filter source)))
              (is (= {:source-id source-id}
                     (source/re-import {:sr-context sr-context} project-2-id source)))
              (is (test/wait-not-importing? system project-2-id 10000))
              (is (= 4 (project/project-article-count project-2-id)))
              (let [title-count #(q/find-count [:article :a] {:a.project-id project-2-id
                                                              :ad.title %}
                                               :join [[:article-data :ad] :a.article-data-id])]
                (is (= 1 (title-count "Sutinen Rosiglitazone.pdf")))
                (is (= 1 (title-count "Plosker Troglitazone.pdf")))))))))))
