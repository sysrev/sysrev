(ns sysrev.test.e2e.search-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.group.core :as group]
   [sysrev.project.core :as project]
   [sysrev.test.e2e.core :as e]
   [sysrev.user.interface :as user]
   [sysrev.util :as util]))

(defmacro fill-search [driver text]
  `(doto ~driver
     (et/is-wait-visible :search-sysrev-bar)
     (ea/fill-human :search-sysrev-bar ~text {:mistake-prob 0 :pause-max 0.01})))

(deftest ^:e2e test-search-users
  (e/with-test-resources [{:keys [driver] :as test-resources} {}]
    (user/create-user "test_user_1@insilica.co" "override")
    (user/create-user "test_user_2@insilica.co" "override")
    (testing "usernames display correctly in search"
      (e/go test-resources "/")
      (doto driver
        (fill-search "test-user")
        (et/is-click-visible [:search-sysrev-form {:tag :button}])
        (et/is-click-visible {:fn/has-text "Users"})
        (et/is-wait-visible {:fn/has-text "test-user-1"}))
      (e/go test-resources "/search?q=test-user&p=1&type=users")
      (et/is-wait-visible driver {:fn/has-text "test-user-1"}))))

(deftest ^:e2e test-search
  (e/with-test-resources [{:keys [driver] :as test-resources} {}]
    (let [strings (for [_ (range 13)] (str/lower-case (util/random-id)))
          [s1 s2 & _] strings
          project-names (->> (for [x strings, y strings, z strings]
                               (str x " " y " " z))
                             (take 35))
          users (->> (for [x strings, y strings]
                       (str x "@" y (nth [".com" ".org" ".net"] (rand-int 3))))
                     (take 10))
          group-names (->> (for [x strings, y strings, z strings]
                             (str x " " y " " z " "
                                  (nth ["LLC." "Inc." "LMTD." "Corp." "Co."] (rand-int 5))))
                           (take 35))]
      (doseq [name project-names]
        (project/create-project name))
      (doseq [email users]
        (user/create-user email "override"))
      (doseq [name group-names]
        (group/create-group! name))
      (e/go test-resources "/")
      (doto driver
        (fill-search s1)
        (et/is-click-visible [:search-sysrev-form {:tag :button}])
        (et/is-wait-visible {:id :search-results-projects-count
                             :fn/text "35"})
        (et/is-wait-visible {:id :search-results-users-count
                             :fn/text "10"})
        (et/is-wait-visible {:id :search-results-orgs-count
                             :fn/text "35"})
        (et/clear :search-sysrev-bar)
        (fill-search s2)
        (et/is-click-visible [:search-sysrev-form {:tag :button}])
        (et/is-wait-visible {:id :search-results-projects-count
                             :fn/text "15"})
        (et/is-wait-visible {:id :search-results-users-count
                             :fn/text "0"})
        (et/is-wait-visible {:id :search-results-orgs-count
                             :fn/text "15"})))))

