(ns sysrev.source.project-filter-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [medley.core :as medley]
            [sysrev.api :as api]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.source.interface :as src]
            [sysrev.test.core :as test]
            [sysrev.user.interface :as user]
            [venia.core :as venia]))

(deftest ^:integration test-import-one-type
  (test/with-test-system [system {}]
    (let [{:keys [api-token user-id]} (test/create-test-user system)]
      (user/change-user-setting user-id :dev-account-enabled? true)
      (testing "Articles can be imported from a project with one type of article"
        (let [project-1-id (-> (api/create-project-for-user!
                                (:web-server system)
                                "Project Filter Import Source"
                                user-id
                                true)
                               :project
                               :project-id)
              project-1-url (str "https://sysrev.com/p/" project-1-id)
              project-2-id (-> (api/create-project-for-user!
                                (:web-server system)
                                "Project Filter Import Target"
                                user-id
                                true)
                               :project
                               :project-id)
              filename "test-pdf-import.zip"
              pdf-zip (-> (str "test-files/" filename) io/resource io/file)]
          (db/with-transaction
            (src/import-source
             {:web-server (:web-server system)}
             :pdf-zip
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
          (let [fut (future
                      (while (-> (q/find [:project-source :ps]
                                         {:ps.project-id project-2-id}
                                         :meta)
                                 first
                                 :importing-articles?))
                      true)]
            (is (true? (deref fut 1000 nil)))
            (future-cancel fut))
          (is (= 4 (project/project-article-count project-2-id)))
          (let [title-count #(q/find-count [:article :a] {:a.project-id project-2-id
                                                          :ad.title %}
                                           :join [[:article-data :ad] :a.article-data-id])]
            (is (= 1 (title-count "Sutinen Rosiglitazone.pdf")))
            (is (= 1 (title-count "Plosker Troglitazone.pdf")))))))))
