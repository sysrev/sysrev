(ns sysrev.test.e2e.project-creation-test
  (:require
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.group.core :as group]
   [sysrev.test.core :as test]
   [sysrev.test.e2e.account :as account]
   [sysrev.test.e2e.core :as e]
   [sysrev.util :as util]))

(deftest ^:e2e test-user-create-new
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)]
      (account/log-in test-resources user)
      (doto driver
        (et/is-click-visible {:css "#new-project.button"})
        ;; private is disabled
        (et/is-wait-visible (str "//p[contains(text(),'Private')]"
                                 "/ancestor::div[contains(@class,'row')]"
                                 "/descendant::div[contains(@class,'radio') and contains(@class,'disabled')]")))
      ;; upgrade plan
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (doto driver
        ea/refresh
        ;; create the private project
        (et/is-wait-visible {:css "#create-project .project-name input"})
        (ea/fill {:css "#create-project .project-name input"} "SysRev Browser Test (test-user-create-new)")
        (et/is-click-visible (str "//p[contains(text(),'Private')]"
                                  "/ancestor::div[contains(@class,'row')]"
                                  "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
        (et/is-click-visible "//button[contains(text(),'Create Project')]")
        ;; is this project private?
        (et/is-wait-visible {:css "i.grey.lock"})
        (et/is-wait-visible "//span[contains(text(),'Private')]")))))

(deftest ^:e2e test-group-create-new
  (e/with-test-resources [{:keys [driver system] :as test-resources} {}]
    (let [{:keys [user-id] :as user} (test/create-test-user system)
          group-name (str "Bravo" (util/random-id))
          group-id (group/create-group! group-name)]
      (group/add-user-to-group! user-id group-id :permissions ["owner"])
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (account/log-in test-resources user)
      (doto driver
        (et/is-click-visible :user-name-link)
        (et/is-click-visible :user-orgs)
        (et/is-click-visible (str "//a[text()='" group-name "']"))
        (et/is-click-visible :new-project)
        ;; create the private project
        (ea/fill {:css "#create-project .project-name input"}
                 "SysRev Browser Test (test-group-create-new)")
        (et/is-click-visible (str "//p[contains(text(),'Private')]"
                                  "/ancestor::div[contains(@class,'row')]"
                                  "/descendant::div[contains(@class,'radio') and not(contains(@class,'disabled'))]"))
        (et/is-click-visible "//button[contains(text(),'Create Project')]")
        ;; is this project private?
        (et/is-wait-visible {:css "i.grey.lock"})
        (et/is-wait-visible "//span[contains(text(),'Private')]")))))
