(ns sysrev.test.db.labels
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute do-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as p]
            [sysrev.db.labels :as l]
            [sysrev.db.articles :as a]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.test.db.core :refer [test-project-ids]]))

(use-fixtures :once default-fixture)

(deftest user-label-task
  (doseq [project-id (test-project-ids)]
    (let [unlabeled (l/unlabeled-articles project-id)]
      (doseq [user-id (p/project-user-ids project-id)]
        (let [n-tests (if (-> (q/select-project-article-labels project-id nil [:%count.*])
                              (q/filter-label-user user-id)
                              (->> do-query first :count (= 0)))
                        2 10)]
          (let [single-labeled (l/single-labeled-articles project-id user-id)]
            (dotimes [i n-tests]
              (let [{:keys [article-id today-count] :as result}
                    (l/get-user-label-task project-id user-id)
                    {:keys [review-status] :as article}
                    (when article-id
                      (a/query-article-by-id-full article-id))]
                (if (and (empty? unlabeled) (empty? single-labeled))
                  (is (nil? result)
                      (format "project=%s,user=%s : %s"
                              project-id user-id
                              "get-user-label-task should return nothing"))
                  (is (s/valid? ::sa/article-partial article)
                      (format "project=%s,user=%s,article=%s : %s"
                              project-id user-id article-id
                              "invalid result")))
                (when result
                  (is (s/valid? ::sc/article-id article-id))
                  (let [labels (l/get-user-article-labels user-id article-id)]
                    (is (empty? labels)
                        (format "project=%s,user=%s,article=%s,status=%s : %s"
                                project-id user-id article-id review-status
                                "user should not have saved labels"))))))))))))
