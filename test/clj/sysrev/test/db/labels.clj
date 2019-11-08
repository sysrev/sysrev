(ns sysrev.test.db.labels
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.spec.alpha :as s]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as p]
            [sysrev.label.core :as l]
            [sysrev.article.core :as a]
            [sysrev.article.assignment :as assign]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.test.core :refer [default-fixture]]))

(use-fixtures :once default-fixture)

(deftest user-label-task
  (doseq [project-id (q/find :project {} :project-id, :limit 10)]
    (let [unlabeled (assign/unlabeled-articles project-id)]
      (doseq [user-id (p/project-user-ids project-id)]
        (let [n-tests (if (-> (q/select-project-article-labels project-id nil [:%count.*])
                              (q/filter-label-user user-id)
                              (->> do-query first :count (= 0)))
                        1 2)
              single-labeled (assign/single-labeled-articles project-id user-id)
              fallback (assign/fallback-articles project-id user-id)]
          (dotimes [_ n-tests]
            (let [{:keys [article-id] :as result}
                  (assign/get-user-label-task project-id user-id)
                  {:keys [review-status] :as article}
                  (some-> article-id (a/get-article))]
              (if (and (empty? unlabeled) (empty? single-labeled) (empty? fallback))
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
                (is (not (l/user-article-confirmed? user-id article-id))
                    (format "project=%s,user=%s,article=%s,status=%s : %s"
                            project-id user-id article-id review-status
                            "user should not have saved labels"))))))))))
