(ns sysrev.test.browser.search
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.api :as api]
            [sysrev.db.core :as db :refer [do-execute]]
            [sysrev.db.groups :as groups]
            [sysrev.project.core :as project]
            [sysrev.db.users :as users]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :refer [xpath]]
            [sysrev.test.core :as test]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def metasyntactic-variables
  ["foo" "bar" "baz" "qux" "quux" "corge" "grault" "garply" "waldo" "fred" "plugh" "xyzzy" "thud"])

(def capitalized-metasyntactic-variables (mapv str/capitalize metasyntactic-variables))

(def search-bar "#search-sysrev-bar")
(def search-bar-form "#search-sysrev-form")
(def projects-count-label "#search-results-projects-count")
(def users-count-label "#search-results-users-count")
(def orgs-count-label "#search-results-orgs-count")

(defn insert-fake-user
  [{:keys [email username]}]
  (-> (insert-into :web-user)
      (values [{:email email :username username}])
      do-execute))

(defn delete-projects-by-name [names]
  (db/clear-query-cache)
  (-> (delete-from :project)
      (where [:in :name (seq names)])
      do-execute))

(defn delete-users-by-email [emails]
  (db/clear-query-cache)
  (-> (delete-from :web-user)
      (where [:in :email (seq emails)])
      do-execute))

(defn delete-groups-by-name [names]
  (db/clear-query-cache)
  (-> (delete-from :groups)
      (where [:in :group-name (seq names)])
      do-execute))

(defn search-for [q]
  (b/set-input-text-per-char search-bar q)
  (taxi/submit search-bar-form))

(defn search-item-count [item]
  (-> (b/get-elements-text (condp = item
                             :projects projects-count-label
                             :users users-count-label
                             :orgs orgs-count-label)) first read-string))

(defn search-counts []
  {:projects (search-item-count :projects)
   :users (search-item-count :users)
   :orgs (search-item-count :orgs)})

(deftest-browser basic-search-test
  (and (test/db-connected?) (not (test/remote-test?)))
  [project-names (->> (for [x capitalized-metasyntactic-variables
                            y capitalized-metasyntactic-variables
                            z capitalized-metasyntactic-variables]
                        (str x " " y " " z))
                      (take 35))
   users (->> (for [x metasyntactic-variables
                    y metasyntactic-variables]
                {:username (str x " " y)
                 :email (str x "@" y
                             (nth [".com" ".org" ".net"] (rand-int 3)))})
              (take 10))
   group-names (->> (for [x capitalized-metasyntactic-variables
                          y capitalized-metasyntactic-variables
                          z capitalized-metasyntactic-variables]
                      (str x " " y " " z " "
                           (nth ["LLC." "Inc." "LMTD." "Corp." "Co."] (rand-int 5))))
                    (take 35))]
  (do
    (nav/go-route "/")
    ;; create fake projects
    (doseq [name project-names] (project/create-project name))
    ;; create fake test users
    (doseq [user users] (insert-fake-user user))
    ;; create fake org names
    (doseq [name group-names] (groups/create-group! name))
    ;; make sure the projects, users and orgs are populated
    (b/is-soon (= 35 (count (project/search-projects "foo" :limit 100))))
    (b/is-soon (= 10 (count (users/search-users "foo" :limit 100))))
    (b/is-soon (= 35 (count (groups/search-groups "foo" :limit 100))))
    ;; search for foo
    (search-for "foo")
    (b/is-soon (= (search-counts) {:projects 35 :users 10 :orgs 35}))
    ;; search for bar
    (search-for "bar")
    (b/is-soon (= (search-counts) {:projects 15 :users 0 :orgs 15})))
  :cleanup (do (log/info "deleting projects:"
                         (delete-projects-by-name project-names))
               (log/info "deleting users:"
                         (delete-users-by-email (map :email users)))
               (log/info "deleting groups:"
                         (delete-groups-by-name group-names))))
