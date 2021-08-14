(ns sysrev.test.etaoin.notifications
  (:require [clojure.string :as str]
            [etaoin.api :as ea]
            [medley.core :as medley]
            [sysrev.api :refer [clone-project-for-org! import-articles-from-pdfs]]
            [sysrev.db.queries :as q]
            [sysrev.group.core :refer [add-user-to-group! create-group!]]
            [sysrev.label.core :refer [add-label-overall-include]]
            [sysrev.label.answer :refer [set-user-article-labels]]
            [sysrev.project.core :refer [create-project]]
            [sysrev.project.invitation :refer [create-invitation!]]
            [sysrev.project.member :refer [add-project-member]]
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

(defn create-projects-and-invitations! [inviter-id user-id]
  (let [project-a (create-project "Mangiferin")
        project-b (create-project "EntoGEM")]
    (create-invitation! user-id (:project-id project-a) inviter-id "paid-reviewer")
    (create-invitation! user-id (:project-id project-b) inviter-id "paid-reviewer")
    [project-a project-b]))

(deftest-etaoin notifications-button
  (let [inviter-id (-> (account/create-account) :email user-by-email :user-id)
        _ (swap! *cleanup-users* conj {:user-id inviter-id})
        _ (account/log-out)
        user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*]
    (testing "Notifications button and drop-down work when empty."
      (doto driver
        (ea/click-visible {:fn/has-class :notifications-icon})
        (ea/wait-visible {:fn/has-text "You don't have any notifications yet"})))
    (let [[project-a]
          #__ (create-projects-and-invitations! inviter-id user-id)]
      (testing "Notifications button and drop-down work."
        (doto driver
          (ea/refresh)
          (ea/wait 2))
        (doto driver
          (ea/wait-visible {:fn/has-class :notifications-count
                            :fn/has-text "2"})
          (ea/click-visible {:fn/has-class :notifications-icon})
          (ea/click-visible [{:fn/has-class :notifications-container}
                             {:fn/has-text (:name project-a)}])
          (ea/wait 1))
        (is (= (str "/user/" user-id "/invitations") (e/get-path)))))))

(deftest-etaoin article-reviewed-notifications
  (let [user-b-email (:email (account/create-account))
        user-b-id (-> user-b-email user-by-email :user-id)
        _ (swap! *cleanup-users* conj {:user-id user-b-id})
        _ (account/log-out)
        user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*
        project-a-id (:project-id (create-project "Mangiferin"))]
    (add-label-overall-include project-a-id)
    (add-project-member project-a-id user-b-id)
    (add-project-member project-a-id user-id)
    (let [{:keys [result]}
          #__ (import-articles-from-pdfs
               project-a-id
               {"files[]"
                {:filename "sysrev-7539906377827440850.pdf"
                 :content-type "application/pdf"
                 :tempfile (util/create-tempfile :suffix ".pdf")
                 :size 0}}
               :user-id user-b-id)
          _ (Thread/sleep 1000)
          article-id (q/find-one [:article-data :ad]
                                 {:a.project-id project-a-id
                                  :ad.title "sysrev-7539906377827440850.pdf"}
                                 :a.article-id
                                 :join [[:article :a] :ad.article-data-id])
          label-id (q/find-one :label
                               {:project-id project-a-id}
                               :label-id)]
      (set-user-article-labels user-b-id article-id
                               {label-id true} :confirm? true)
      (testing ":project-has-new-article notifications"
        (is (true? (:success result)))
        (doto driver
          (ea/wait 1)
          (ea/reload)
          (ea/click-visible {:fn/has-class :notifications-icon})
          (ea/wait-visible {:fn/has-class :notifications-footer})
          (ea/click-visible [{:fn/has-class :notifications-container}
                             {:fn/has-text "new article review"}])
          (ea/wait 1)
          (is (str/ends-with? (e/get-path) (str "/p/" project-a-id "/article/" article-id))))))))

(deftest-etaoin group-has-new-project-notifications
  (let [user-b-email (:email (account/create-account))
        user-b-id (-> user-b-email user-by-email :user-id)
        _ (swap! *cleanup-users* conj {:user-id user-b-id})
        _ (account/log-out)
        user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*
        project-a-id (:project-id (create-project "EntoGEM"))
        group-id (create-group! "TestGroupA")
        _ (add-user-to-group! user-b-id group-id :permissions ["admin"])
        _ (add-user-to-group! user-id group-id :permissions ["admin"])
        {:keys [dest-project-id]}
        #__ (clone-project-for-org!
             {:src-project-id project-a-id :user-id user-b-id :org-id group-id})]
    (testing ":group-has-new-project notifications"
      (doto driver
        (ea/wait 1)
        (ea/reload)
        (ea/click-visible {:fn/has-class :notifications-icon})
        (ea/wait-visible {:fn/has-class :notifications-footer})
        (ea/wait-visible [{:fn/has-class :notifications-container}
                          {:fn/has-text "EntoGEM"}]))
      (is (ea/visible? driver [{:fn/has-class :notifications-container}
                               {:fn/has-text "TestGroupA"}]))
      (doto driver
        (ea/click [{:fn/has-class :notifications-container}
                   {:fn/has-text "TestGroupA"}])
        (ea/wait 1))
      (is (str/ends-with? (e/get-path) (str "/p/" dest-project-id "/add-articles"))))))

(deftest-etaoin project-has-new-article-notifications
  (let [user-b-email (:email (account/create-account))
        user-b-display (first (str/split user-b-email #"@"))
        user-b-id (-> user-b-email user-by-email :user-id)
        _ (swap! *cleanup-users* conj {:user-id user-b-id})
        _ (account/log-out)
        user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*
        project-a-id (:project-id (create-project "Mangiferin"))]
    (add-project-member project-a-id user-b-id)
    (add-project-member project-a-id user-id)
    (let [{:keys [result]}
          #__ (import-articles-from-pdfs
               project-a-id
               {"files[]"
                {:filename "sysrev-7539906377827440850.pdf"
                 :content-type "application/pdf"
                 :tempfile (util/create-tempfile :suffix ".pdf")
                 :size 0}}
               :user-id user-b-id)]
      (testing ":project-has-new-article notifications"
        (is (true? (:success result)))
        (doto driver
          (ea/wait 1)
          (ea/reload)
          (ea/click-visible {:fn/has-class :notifications-icon})
          (ea/wait-visible {:fn/has-class :notifications-footer})
          (ea/wait-visible {:fn/has-text "Mangiferin"}))
        (is (ea/visible? driver {:fn/has-text user-b-display}))
        (is (ea/visible? driver {:fn/has-text "added a new article"}))
        (doto driver
          (ea/click {:fn/has-text "Mangiferin"})
          (ea/wait 1))
        (let [article-id (q/find-one [:article-data :ad]
                                     {:a.project-id project-a-id
                                      :ad.title "sysrev-7539906377827440850.pdf"}
                                     :a.article-id
                                     :join [[:article :a] :ad.article-data-id])]
          (is (str/ends-with? (e/get-path) (str "/p/" project-a-id "/article/" article-id))))))))

(deftest-etaoin project-has-new-user-notifications
  (let [new-user-email (:email (account/create-account))
        new-user-display (first (str/split new-user-email #"@"))
        new-user-id (-> new-user-email user-by-email :user-id)
        _ (swap! *cleanup-users* conj {:user-id new-user-id})
        _ (account/log-out)
        user (account/create-account)
        user-id (:user-id (user-by-email (:email user)))
        _ (swap! *cleanup-users* conj {:user-id user-id})
        driver @e/*driver*
        project-a-id (:project-id (create-project "Mangiferin"))]
    (add-project-member project-a-id user-id)
    (add-project-member project-a-id new-user-id)
    (testing ":project-has-new-user notifications"
      (doto driver
        (ea/wait 1)
        (ea/reload)
        (ea/click-visible {:fn/has-class :notifications-icon})
        (ea/wait-visible {:fn/has-class :notifications-footer}))
      (is (ea/visible? driver {:fn/has-text "Mangiferin"}))
      (is (ea/visible? driver {:fn/has-text new-user-display}))
      (doto driver
        (ea/click {:fn/has-text "Mangiferin"})
        (ea/wait 1))
      (is (str/ends-with? (e/get-path) (str "/p/" project-a-id "/users"))))))
