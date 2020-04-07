(ns sysrev.test.web.routes.clone
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [sysrev.project.core :as project]
            [sysrev.project.member :refer [add-project-member]]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b]
            [sysrev.test.web.routes.utils :refer [route-response-fn]]
            [sysrev.util :as util]))

(use-fixtures :once default-fixture)

(defmacro with-cleanup-users [user-emails & body]
  `(try ~@body
        (finally (doseq [email# ~user-emails]
                   (b/cleanup-test-user! :email email#)))))

(deftest clone-user-project-test
  (let [handler (sysrev-handler)
        route-response (route-response-fn handler)
        test-user-a (b/create-test-user)
        test-user-b (b/create-test-user :email "test-user-b@sysrev.com" :password "foobar")
        project-name "Clone SRC Test"
        test-project-name (str project-name " " (util/random-id))]
    (with-cleanup-users [(:email test-user-a) (:email test-user-b)]
      ;; login this user
      (is (get-in (route-response :post "/api/auth/login" test-user-a)
                  [:result :valid]))
      ;; Create a project
      (let [create-project-response (route-response :post "/api/create-project"
                                                    {:project-name test-project-name})
            new-project-id (get-in create-project-response [:result :project :project-id])]
        ;; create a project for this user
        (is (get-in create-project-response [:result :success]))
        ;; get the article count, should be 0
        (is (= 0 (project/project-article-count new-project-id)))
        ;; set this project to private
        (is (not (:public-access (project/change-project-setting new-project-id :public-access false))))
        ;; user can clone their project
        (is (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                    [:result :success]))
        ;;.. but another user can't clone it
        (is (get-in (route-response :post "/api/auth/login" test-user-b)
                    [:result :valid]))
        (is (= (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                       [:error :message])
               "You don't have permission to clone that project"))))))

(deftest clone-org-project-test
  (let [handler (sysrev-handler)
        route-response (route-response-fn handler)
        test-user-a (b/create-test-user)
        test-user-b (b/create-test-user :email "test-user-b@sysrev.com" :password "foobar")
        project-name "Clone SRC Test"
        test-project-name (str project-name " " (util/random-id))
        test-org "Alpha Org"
        org-name (str test-org " " (util/random-id))        ]
    (with-cleanup-users [(:email test-user-a) (:email test-user-b)]
      ;; login this user
      (is (get-in (route-response :post "/api/auth/login" test-user-a)
                  [:result :valid]))
      ;; Create an org and project
      (let [org (route-response :post "/api/org" {:org-name org-name})
            new-org-id (get-in org [:result :id])
            create-project-response (route-response
                                     :post (str "/api/org/" new-org-id "/project")
                                     {:project-name test-project-name})
            new-project-id (get-in create-project-response [:result :project :project-id])]
        ;; create a new-project
        (is (get-in create-project-response [:result :success]))
        ;; set this project to private
        (is (not (:public-access (project/change-project-setting new-project-id :public-access false))))
        ;; The org owner can clone this project
        (is (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                    [:result :success]))
        ;; but someone else can't
        (add-project-member new-project-id (:user-id test-user-b))
        (is (get-in (route-response :post "/api/auth/login" test-user-b)
                    [:result :valid]))
        (is (= (get-in (route-response :post "/api/clone-project" {:src-project-id new-project-id})
                       [:error :message])
               "You don't have permission to clone that project"))
        ;; someone else also can't clone the project to their own group
        (let [test-org-b "Bravo Org"
              org-name-b (str test-org-b " " (util/random-id))
              org-b (route-response :post "/api/org" {:org-name org-name-b})
              org-b-id (get-in org-b [:result :id])]
          (is (= (get-in (route-response :post (str "/api/org/" org-b-id  "/project/clone")
                                         {:src-project-id new-project-id})
                         [:error :message])
                 "You don't have permission to clone that project")))))))

