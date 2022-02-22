(ns sysrev.test.e2e.project-compensation-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.db.core :as db]
   [sysrev.db.queries :as q]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.project.member :as member]
   [sysrev.source.import :as import]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as e-project]))

(defn review-article! [project-id user-id article-id]
  (db/with-clear-project-cache project-id
    (doseq [label (q/find :label {:project-id project-id})]
      (condp = (:value-type label)
        "boolean" (q/create :article-label
                              {:article-label-local-id (:label-id-local label)
                               :article-id article-id
                               :label-id (:label-id label)
                               :user-id user-id
                               :answer (db/to-jsonb true)
                               :imported false
                               :confirm-time db/sql-now})))))

(deftest ^:optional test-project-compensation
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          reviewer-1 (test/create-test-user system)
          reviewer-2 (test/create-test-user system)
          project-id (e-project/create-project! test-resources "Sysrev Compensation Test")]
      (import/import-pmid-vector
       (select-keys system [:web-server])
       project-id
       {:pmids [33222245 32891636 25706626]}
       {:use-future? false})
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (testing "Project owner can set compensation levels"
        (e/go-project test-resources project-id "/compensations")
        (doto driver
          (et/is-fill-visible :create-compensation-amount "1")
          (et/is-click-visible {:css ".add-rate button[type=submit]"})
          e/wait-until-loading-completes
          (et/is-wait-visible (str "//*[@id='project-rates']"
                                   "/descendant::*[contains(text(),'$1.00')]"))
          (et/is-click-visible [{:fn/has-class :user-compensation-entry}
                                {:fn/has-class :dropdown}
                                {:fn/text "None"}])
          (et/is-click-visible {:fn/text "$1.00 / article"})
          e/wait-until-loading-completes))
      (testing "Reviewer compensation amounts are correct"
        (member/add-project-member project-id (:user-id reviewer-1))
        (member/add-project-member project-id (:user-id reviewer-2))
        (doseq [article-id (q/find :article
                                   {:project-id project-id}
                                   :article-id
                                   :limit 2)]
          (review-article! project-id (:user-id reviewer-1) article-id))
        (doseq [article-id (q/find :article
                                   {:project-id project-id}
                                   :article-id
                                   :limit 3)]
          (review-article! project-id (:user-id reviewer-2) article-id))
        (doto driver
          e/refresh
          (et/is-wait-visible (str "//*[@id='reviewer-amounts']"
                                   "/descendant::*[@data-username='" (:username reviewer-1) "']"
                                   "/descendant::*[contains(text(),'$2.00')]"))
          (et/is-wait-visible (str "//*[@id='reviewer-amounts']"
                                   "/descendant::*[@data-username='" (:username reviewer-2) "']"
                                   "/descendant::*[contains(text(),'$3.00')]"))))
      (testing "Project owner can add funds via PayPal"
        (let [test-id #(str "//*[@data-testid='" (name %) "']")
              window-size (ea/get-window-size driver)
              card-num-qs [:cc (test-id :cardNumber)]
              expiry-qs [:expiry_value :cardExpiry]
              cvv-qs [:cvv :cardCvv]
              phone-qs [:telephone :phone]
              submit-qs [:pomaSubmit (test-id :pomaGuestSubmitButton)]
              ;; PayPal HTML varies. These let us find which query is valid.
              qq (fn [driver q]
                   (try
                     (ea/query driver q)
                     q
                     (catch clojure.lang.ExceptionInfo e
                       (when-not (some->> e ex-data :response :value :message
                                          (re-find #"^no such element:.*"))
                         (throw e)))))
              some-q (fn [driver qs]
                       (some (partial qq driver) qs))]
          (et/is-fill-visible driver :paypal-amount "20")
          (et/is-wait-pred #(e/js-execute driver "return typeof paypal !== 'undefined'"))
          (ea/wait 1) ;; flakey
          (et/is-click-visible driver {:fn/has-class :paypal-button})
          (et/is-wait-pred #(second (ea/get-window-handles driver)))
          (doto driver
            ea/switch-window-next
            (ea/set-window-size window-size)
            (et/is-click-visible :createAccount {:timeout 30}))
          (et/is-wait-pred #(some-q driver card-num-qs))
          (et/is-wait-pred #(some-q driver expiry-qs))
          (et/is-wait-pred #(some-q driver cvv-qs))
          (et/is-wait-pred #(some-q driver phone-qs))
          (et/is-wait-pred #(some-q driver submit-qs))
          (doto driver
            (et/is-fill-visible (some-q driver card-num-qs) "4032038001593510" {:timeout 30})
            (et/is-fill-visible (some-q driver expiry-qs) "11/24")
            (et/is-fill-visible (some-q driver cvv-qs) "123")
            (try ;; This is not always present
              ;; Click it before we get too far down the page, where it could
              ;; block an element.
              (et/click-visible driver :acceptAllButton)
              (catch clojure.lang.ExceptionInfo _))
            (et/is-fill-visible :firstName "John")
            (et/is-fill-visible :lastName "Doe")
            (et/is-fill-visible :billingLine1 "1 Infinite Loop Dr")
            (et/is-fill-visible :billingCity "Baltimore")
            (ea/select :billingState "Maryland")
            (et/is-fill-visible :billingPostalCode "21209")
            (et/is-fill-visible (some-q driver phone-qs) "222-333-4444")
            (et/is-fill-visible :email "sb-477vju643771@personal.example.com")
            (et/is-click-visible (some-q driver submit-qs))
            (ea/switch-window (first (ea/get-window-handles driver)))
            (et/is-wait-visible {:fn/text "Payment Processed"} {:timeout 30}))))
      (testing "Project owner can pay reviewers"
        (doto driver
          (et/is-click-visible {:css (str "#reviewer-amounts"
                                          " .item[data-username=" (pr-str (:username reviewer-1)) "]"
                                          " .button.pay-user")})
          (et/is-click-visible {:css ".button.confirm-pay-user"})
          (et/is-wait-visible (str "//*[@id='reviewer-amounts']"
                                   "/descendant::*[@data-username='" (:username reviewer-1) "']"
                                   "/descendant::*[contains(text(),'$0.00')]"))
          (et/is-wait-visible (str "//*[@id='reviewer-amounts']"
                                   "/descendant::*[@data-username='" (:username reviewer-2) "']"
                                   "/descendant::*[contains(text(),'$3.00')]")))))))
