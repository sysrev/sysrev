(ns sysrev.test.e2e.review-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.api :as api]
   [sysrev.label.core :as label]
   [sysrev.project.core :as project]
   [sysrev.project.member :as member]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]))

(deftest ^:e2e test-disabled-required-label
  (e/with-test-resources [{:keys [driver system] :as test-resources}]
    (let [{:keys [user-id] :as user} (test/create-test-user)
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
        (doto driver
          (ea/wait-visible {:fn/has-text "Your Projects"})
          (ea/wait 1)
          (ea/refresh))
        (e/go test-resources (str "/p/" project-id "/review"))
        (doto driver
          (e/click-visible [{:fn/has-class "label-edit"}
                            {:fn/has-text "Yes"}])
          (ea/wait 1)
          (-> (ea/has-class? {:fn/has-class "save-labels"} "disabled")
              not is)
          (e/click-visible [{:fn/has-class "label-edit"}
                            {:fn/has-text "?"}])
          (ea/wait 1)
          (-> (ea/has-class? {:fn/has-class "save-labels"} "disabled")
              is))))))
