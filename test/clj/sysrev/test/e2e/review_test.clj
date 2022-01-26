(ns sysrev.test.e2e.review-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.api :as api]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.label.core :as label]
   [sysrev.project.core :as project]
   [sysrev.project.member :as member]
   [sysrev.source.import :as import]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.labels :as labels]
   [sysrev.test.e2e.project :as e-project]
   [sysrev.test.xpath :as xpath]))

(deftest ^:e2e test-disabled-required-label
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          project-id (:project-id (project/create-project "Disabled Required Label"))]
      (member/add-project-member project-id user-id
                                 :permissions ["admin" "member"])
      (api/import-articles-from-pdfs
       {:web-server (:web-server system)}
       project-id
       {"files[]"
        {:filename "sysrev-7539906377827440850.pdf"
         :content-type "application/pdf"
         :tempfile (io/resource "test-files/test-pdf-import/Weyers Ciprofloxacin.pdf")}}
       :user-id user-id)
      (label/add-label-overall-include project-id)
      (label/add-label-entry-boolean project-id
                                     {:enabled false
                                      :name "bool"
                                      :question "bool"
                                      :required true
                                      :short-label "bool"})
      (account/log-in test-resources user)
      (testing "Disabled required label doesn't prevent saving"
        (e/go-project test-resources project-id "/review")
        (doto driver
          (et/click-visible [{:fn/has-class "label-edit"}
                             {:fn/has-text "Yes"}])
          e/wait-until-loading-completes
          (-> (ea/has-class? {:fn/has-class "save-labels"} "disabled")
              not is)
          (et/click-visible [{:fn/has-class "label-edit"}
                             {:fn/has-text "?"}])
          e/wait-until-loading-completes
          (-> (ea/has-class? {:fn/has-class "save-labels"} "disabled")
              is))))))

;; This was commented out at https://github.com/insilica/systematic_review/blob/6c88a410e51116ee7c1826aa30215fd1994c029a/test/clj/sysrev/test/browser/review_articles.clj#L210
(deftest ^:kaocha/pending ^:e2e test-create-project-and-review-article)

;; This was commented out at https://github.com/insilica/systematic_review/blob/6c88a410e51116ee7c1826aa30215fd1994c029a/test/clj/sysrev/test/browser/review_articles.clj#L327
(deftest ^:kaocha/pending ^:e2e test-review-label-components)

;; This was commented out at https://github.com/insilica/systematic_review/blob/6c88a410e51116ee7c1826aa30215fd1994c029a/test/clj/sysrev/test/browser/review_articles.clj#L429
(deftest ^:kaocha/pending ^:e2e test-articles-data)

(deftest ^:e2e test-review-skipping
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (testing "Recently assigned articles are moved to the end of the queue"
      (let [project-id (e-project/create-project! test-resources "last-assigned-test")]
        (import/import-pmid-vector
         (select-keys system [:web-server])
         project-id
         {:pmids [25706626 25215519 23790141 22716928 19505094 9656183]}
         {:use-future? false})
        (e/go-project test-resources project-id "/review")
        (et/is-click-visible driver xpath/review-labels-tab)
        (is (= #{"A common variant map"
                 "A family of rare ear"
                 "Effects of a soybean"
                 "Important roles of e"
                 "Strong CO2 binding i"
                 "Tooth position index"}
               (->> (range 6)
                    (map
                     (fn [_]
                       (doto driver
                         (et/is-click-visible {:css ".ui.button.skip-article"})
                         e/wait-until-loading-completes)
                       (-> (ea/get-element-text driver {:css ".ui.segment.article-content"})
                           (or "")
                           (subs 0 20))))
                    set))
            "No articles are repeated while clicking Skip")))))

(deftest ^:e2e test-unlimited-reviews-setting
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (let [project-id (e-project/create-project! test-resources "Unlimited Reviews Test")]
      (e/go-project test-resources project-id "/settings")
      (testing "can enable unlimited reviews setting"
        (project/change-project-setting project-id :unlimited-reviews false)
        (doto driver
          (et/is-click-visible :unlimited-reviews_true)
          (et/is-click-visible {:css ".project-options button.save-changes"})
          e/wait-until-loading-completes)
        (is (:unlimited-reviews (project/project-settings project-id)))))))

(deftest ^:e2e test-unlimited-reviews
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [users (vec (repeatedly 3 #(test/create-test-user system)))
          _ (account/log-in test-resources (first users))
          project-id (e-project/create-project! test-resources "Unlimited Reviews Test")]
      (project/change-project-setting project-id :unlimited-reviews true)
      (import/import-pmid-vector
       (select-keys system [:web-server])
       project-id
       {:pmids [25706626 25215519 23790141]}
       {:use-future? false})
      (doseq [{:keys [user-id]} (rest users)]
        (member/add-project-member project-id user-id))
      (e/go-project test-resources project-id "/review")
      (dotimes [_ 2]
        (doto driver
          (labels/set-label-answer! (assoc labels/include-label-definition :value true))
          (et/is-click-visible {:css ".button.save-labels"})
          e/wait-until-loading-completes))
      (doseq [u (rest users)]
        (doto test-resources
          account/log-out
          (account/log-in u)
          (e/go-project project-id "/review"))
        (dotimes [_ 3]
          (doto driver
            (labels/set-label-answer! (assoc labels/include-label-definition :value true))
            (et/is-click-visible {:css ".button.save-labels"})
            e/wait-until-loading-completes)))
      (doto test-resources
        account/log-out
        (account/log-in (first users))
        (e/go-project project-id "/review"))
      (et/is-wait-visible
       driver {:css ".button.save-labels"} {}
       "User can review this article even though it already has 2 reviews")
      (testing "User cannot review with :unlimited-reviews false"
        (project/change-project-setting project-id :unlimited-reviews false)
        (e/refresh driver)
        (et/is-wait-visible driver {:fn/has-class :no-review-articles}))
      (testing "User can review after re-enabling :unlimited-reviews"
        (project/change-project-setting project-id :unlimited-reviews true)
        (doto driver
          e/refresh
          (labels/set-label-answer! (assoc labels/include-label-definition :value true))
          (et/is-click-visible {:css ".button.save-labels"})
          e/wait-until-loading-completes)))))
