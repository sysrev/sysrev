(ns sysrev.test.e2e.pages-test
  (:require
   [clojure.test :refer :all]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]))

(deftest ^:e2e ^:remote test-terms-of-use-anonymous
  (e/with-test-resources [{:keys [driver] :as test-resources} {}]
    (testing "terms of use page works for anonymous users"
      (testing "works from direct url"
        (e/go test-resources "/terms-of-use")
        (et/is-wait-visible driver {:css "h2#preamble"}))
      (testing "works from footer link"
        (e/go test-resources "/")
        (doto driver
          (et/is-click-visible {:css "#footer a#terms-link"})
          (et/is-wait-visible {:css "h2#preamble"}))))))

(deftest ^:e2e test-terms-of-use-logged-in
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (account/log-in test-resources (test/create-test-user system))
    (testing "terms of use page works for logged-in users"
      (testing "works from direct url"
        (e/go test-resources "/terms-of-use")
        (et/is-wait-visible driver {:css "h2#preamble"}))
      (testing "works from footer link"
        (e/go test-resources "/")
        (doto driver
          (et/is-click-visible {:css "#footer a#terms-link"})
          (et/is-wait-visible {:css "h2#preamble"}))))))
