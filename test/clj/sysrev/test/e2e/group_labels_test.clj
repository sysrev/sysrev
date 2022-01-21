(ns sysrev.test.e2e.group-labels-test
  (:require
   [clojure.test :refer :all]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as e-project]
   [sysrev.util :as util]))

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/group_labels.clj#L181
(deftest ^:kaocha/pending ^:e2e test-happy-path)

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/group_labels.clj#L293
(deftest ^:kaocha/pending ^:e2e test-error-handling)

(deftest ^:e2e test-paywall
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in
                   test-resources
                   (test/create-test-user system {:email "browser_test@example.com"}))
          project-name (str "Group Label Paywall Test " (util/random-id))
          project-id (e-project/create-project! test-resources project-name)]
      (testing "Paywall is in place"
        (e/go-project test-resources project-id "/labels/edit")
        (et/is-wait-visible driver :group-label-paywall)
        (et/is-not-visible? driver {:fn/text "Add Group Label"}))
      (testing "Paywall is lifted for paid plans"
        (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
        (doto driver
          e/refresh
          (et/is-wait-visible {:fn/text "Add Group Label"})
          (et/is-not-visible? :group-label-paywall))))))

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/group_labels.clj#L454
(deftest ^:kaocha/pending ^:e2e test-in-depth)

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/group_labels.clj#L574
(deftest ^:kaocha/pending ^:e2e test-consistent-label-ordering)

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/group_labels.clj#L631
(deftest ^:kaocha/pending ^:e2e test-label-consensus)

;; This was commented out at https://github.com/insilica/systematic_review/blob/202ce044271e0a367b504748ad3ecd270ed89801/test/clj/sysrev/test/browser/group_labels.clj#L779
(deftest ^:kaocha/pending ^:e2e test-csv-download)
