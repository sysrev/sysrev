(ns sysrev.test.etaoin.review
  (:require [clojure.string :as str]
            [etaoin.api :as ea]
            [medley.core :as medley]
            [sysrev.api :as api]
            [sysrev.label.core :as label]
            [sysrev.project.core :as project]
            [sysrev.project.member :as member]
            [sysrev.user.core :refer [user-by-email]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.etaoin.core :as e :refer
             [*cleanup-users* deftest-etaoin etaoin-fixture]]
            [sysrev.test.etaoin.account :as account]
            [sysrev.util :as util])
  (:use clojure.test
        etaoin.api))

(use-fixtures :once default-fixture)
(use-fixtures :each etaoin-fixture)

(deftest-etaoin test-disabled-required-label
  (let [user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*
        project-id (:project-id (project/create-project "Disabled Required Label"))]
    (member/add-project-member project-id user-id
                               :permissions ["admin" "member"])
    (api/import-articles-from-pdfs
         project-id
         {"files[]"
          {:filename "sysrev-7539906377827440850.pdf"
           :content-type "application/pdf"
           :tempfile (util/create-tempfile :suffix ".pdf")
           :size 0}}
         :user-id user-id)
    (label/add-label-overall-include project-id)
    (label/add-label-entry-boolean project-id
                                   {:enabled false
                                    :name "bool"
                                    :question "bool"
                                    :required true
                                    :short-label "bool"})
    (testing "Disabled required label doesn't prevent saving"
      (doto driver
        (ea/wait-visible {:fn/has-text "Your Projects"})
        (ea/wait 1)
        (ea/reload)
        #_:clj-kondo/ignore
        (do (e/go (str "/p/" project-id "/review")))
        (ea/click-visible [{:fn/has-class "label-edit"}
                           {:fn/has-text "Yes"}])
        (ea/wait 1)
        (-> (ea/has-class? {:fn/has-class "save-labels"} "disabled")
            not is)
        (ea/click-visible [{:fn/has-class "label-edit"}
                           {:fn/has-text "?"}])
        (ea/wait 1)
        (-> (ea/has-class? {:fn/has-class "save-labels"} "disabled")
            is)))))
