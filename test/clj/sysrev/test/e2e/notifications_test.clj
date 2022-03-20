(ns sysrev.test.e2e.notifications-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.api :refer [clone-project-for-org! import-articles-from-pdfs]]
   [sysrev.db.queries :as q]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.group.core :refer [add-user-to-group! create-group!]]
   [sysrev.label.answer :refer [set-user-article-labels]]
   [sysrev.label.core :refer [add-label-overall-include]]
   [sysrev.project.core :refer [create-project]]
   [sysrev.project.invitation :as invitation]
   [sysrev.project.member :refer [add-project-member]]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.e2e.project :as e-project]
   [sysrev.util :as util]))

(defn create-projects-and-invitations! [inviter-id user-id]
  (let [project-a (create-project "Mangiferin")
        project-b (create-project "EntoGEM")]
    (invitation/create-invitation! user-id (:project-id project-a) inviter-id "paid-reviewer")
    (invitation/create-invitation! user-id (:project-id project-b) inviter-id "paid-reviewer")
    [project-a project-b]))

(deftest ^:optional notifications-button
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [inviter-id (:user-id (test/create-test-user system))
          {:keys [user-id] :as user} (test/create-test-user system)]
      (account/log-in test-resources user)
      (testing "Notifications button and drop-down work when empty."
        (doto driver
          (et/is-click-visible {:fn/has-class :notifications-icon})
          (et/is-wait-visible {:fn/has-text "You don't have any notifications yet"})))
      (let [[project-a] (create-projects-and-invitations! inviter-id user-id)]
        (testing "Notifications button and drop-down work."
          (doto driver
            (ea/refresh)
            (et/is-wait-visible {:fn/has-class :notifications-count
                              :fn/has-text "2"})
            (et/is-click-visible {:fn/has-class :notifications-icon})
            (et/is-click-visible [{:fn/has-class :notifications-container}
                                  {:fn/has-text (:name project-a)}]))
          (is (nil? (ea/wait-predicate
                     #(= (str "/user/" user-id "/invitations") (e/get-path driver))))))))))

(deftest ^:e2e article-reviewed-notifications
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [importer-id (:user-id (test/create-test-user system))
          {:keys [user-id] :as user} (test/create-test-user system)
          project-a-id (:project-id (create-project "Mangiferin"))]
      (account/log-in test-resources user)
      (add-label-overall-include project-a-id)
      (add-project-member project-a-id importer-id)
      (add-project-member project-a-id user-id)
      (let [{:keys [result]}
            #__ (import-articles-from-pdfs
                 {:web-server (:web-server system)}
                 project-a-id
                 {"files[]"
                  {:filename "sysrev-7539906377827440851.pdf"
                   :content-type "application/pdf"
                   :tempfile (util/create-tempfile :suffix ".pdf")
                   :size 0}}
                 :user-id importer-id)
            _ (Thread/sleep 1000)
            article-id (q/find-one [:article-data :ad]
                                   {:a.project-id project-a-id
                                    :ad.title "sysrev-7539906377827440851.pdf"}
                                   :a.article-id
                                   :join [[:article :a] :ad.article-data-id])
            label-id (q/find-one :label
                                 {:project-id project-a-id}
                                 :label-id)]
        (set-user-article-labels importer-id article-id
                                 {label-id true} :confirm? true)
        (testing ":article-reviewed notifications"
          (is (true? (:success result)))
          (doto driver
            (et/is-click-visible {:fn/has-class :notifications-icon})
            (et/is-wait-visible {:fn/has-class :notifications-footer})
            (et/click-visible [{:fn/has-class :notifications-container}
                              {:fn/has-text "new article review"}])
            (is (nil? (ea/wait-predicate
                       #(str/ends-with? (e/get-path driver) (str "/p/" project-a-id "/article/" article-id)))))))))))

(deftest ^:e2e group-has-new-project-notifications
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [creator-id (:user-id (test/create-test-user system))
          {:keys [user-id] :as user} (test/create-test-user system)
          project-a-id (:project-id (create-project "EntoGEM"))
          group-id (create-group! "TestGroupA")
          _ (add-user-to-group! creator-id group-id :permissions ["admin"])
          _ (add-user-to-group! user-id group-id :permissions ["admin"])
          {:keys [dest-project-id]}
          #__ (clone-project-for-org!
               {:src-project-id project-a-id :user-id creator-id :org-id group-id})]
      (account/log-in test-resources user)
      (testing ":group-has-new-project notifications"
        (doto driver
          (et/is-click-visible {:fn/has-class :notifications-icon})
          (et/is-wait-visible {:fn/has-class :notifications-footer})
          (et/is-wait-visible [{:fn/has-class :notifications-container}
                            {:fn/has-text "EntoGEM"}])
          (et/is-click-visible [{:fn/has-class :notifications-container}
                                {:fn/has-text "TestGroupA"}]))
        (is (nil? (ea/wait-predicate
                   #(str/ends-with? (e/get-path driver) (str "/p/" dest-project-id "/add-articles")))))))))

(deftest ^:e2e project-source-added-notifications
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [article-adder (test/create-test-user system)
          {:keys [user-id] :as user} (test/create-test-user system)
          project-a-id (:project-id (create-project "Mangiferin"))]
      (account/log-in test-resources user)
      (add-project-member project-a-id (:user-id article-adder))
      (add-project-member project-a-id user-id)
      (let [{:keys [result]}
            #__ (import-articles-from-pdfs
                 {:web-server (:web-server system)}
                 project-a-id
                 {"files[]"
                  {:filename "sysrev-7539906377827440850.pdf"
                   :content-type "application/pdf"
                   :tempfile (util/create-tempfile :suffix ".pdf")
                   :size 0}}
                 :user-id (:user-id article-adder))]
        (testing ":project-source-added notifications"
          (is (true? (:success result)))
          (doto driver
            (et/is-click-visible {:fn/has-class :notifications-icon})
            (et/is-wait-visible {:fn/has-class :notifications-footer})
            (et/is-wait-visible {:fn/has-text (:username article-adder)})
            (et/is-wait-visible {:fn/has-text "added a new article source"})))))))

(deftest ^:e2e project-has-new-user-notifications
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [new-user (test/create-test-user system)
          {:keys [user-id] :as user} (test/create-test-user system)
          project-a-id (:project-id (create-project "Mangiferin"))]
      (account/log-in test-resources user)
      (add-project-member project-a-id user-id)
      (add-project-member project-a-id (:user-id new-user))
      (testing ":project-has-new-user notifications"
        (doto driver
          (et/is-click-visible {:fn/has-class :notifications-icon})
          (et/is-wait-visible {:fn/has-class :notifications-footer})
          (et/is-wait-visible {:fn/has-text (:username new-user)})
          (et/is-click-visible {:fn/has-text "Mangiferin"}))
        (is (nil? (ea/wait-predicate
                   #(str/ends-with? (e/get-path driver) (str "/p/" project-a-id "/users")))))))))

(deftest ^:e2e test-notifications-page
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [user-id (account/log-in test-resources (test/create-test-user system))
          {inviter-id :user-id} (test/create-test-user system)
          project-id (e-project/create-project! test-resources "test-notifications-page")]
      (testing "Notifications page works when empty."
        (e/go test-resources "/")
        (doto driver
          (et/is-click-visible {:fn/has-class :notifications-icon})
          (et/is-click-visible {:fn/has-class :notifications-footer})
          (et/is-wait-visible {:fn/has-text "You don't have any notifications yet"})))
      (testing "Notifications page works with project invitations"
        (invitation/create-invitation! user-id project-id inviter-id "Project invitation")
        (doto driver
          e/refresh
          (et/is-click-visible {:fn/has-class :notifications-icon})
          (et/is-click-visible {:fn/has-class :notifications-footer})
          (et/is-click-visible {:fn/has-text "test-notifications-page"})
          (et/is-wait-visible :user-invitations))))))
