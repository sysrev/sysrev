(ns sysrev.test.browser.search
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.api :as api]
            [sysrev.db.core :as db :refer [do-execute]]
            [sysrev.db.groups :as groups]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :refer [xpath]]
            [sysrev.test.core :as test]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def metasyntactic-variables ["foo" "bar" "baz" "qux" "quux" "corge" "grault" "garply" "waldo" "fred" "plugh" "xyzzy" "thud"])
(def capitalized-metasyntactic-variables (mapv clojure.string/capitalize metasyntactic-variables))

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

(defn delete-projects-matching-term [q]
  (->> (project/search-projects q :limit 100)
       (map :project-id)
       (map project/delete-project)))

(defn delete-users-matching-term [q]
  (->> (users/search-users q :limit 100)
       (map :user-id)
       (map users/delete-user)))

(defn delete-orgs-matching-term [q]
  (->> (groups/search-groups q :limit 100)
       (map :group-id)
       (map groups/delete-group!)))

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
  (test/db-connected?)
  [search-term "foo"]
  (do
    (nav/go-route "/")
    ;; create fake projects
    (doall (map project/create-project (take 35 (for [x capitalized-metasyntactic-variables y capitalized-metasyntactic-variables z capitalized-metasyntactic-variables] (str x " " y " " z)))))
    ;; create fake test users
    (doall (map insert-fake-user
                (->> (for [x metasyntactic-variables y metasyntactic-variables] {:username (str x " " y) :email (str x "@" y (nth [".com" ".org" ".net"] (rand-int 3)))}) (take 10))))
    ;; create fake org names
    (doall
     (map groups/create-group! (->> (for [x capitalized-metasyntactic-variables y capitalized-metasyntactic-variables z capitalized-metasyntactic-variables] (str x " " y " " z " " (nth ["LLC." "Inc." "LMTD." "Corp." "Co."] (rand-int 5)))) (take 35))))
    ;; make sure the projects, users and orgs are populated
    (b/wait-until #(= 35 (count (project/search-projects "foo" :limit 100))))
    (b/wait-until #(= 10 (count (users/search-users "foo" :limit 100))))
    (b/wait-until #(= 35 (count (groups/search-groups "foo" :limit 100))))
    ;; search for foo
    (search-for "foo")
    (b/wait-until #(= {:projects 35 :users 10 :orgs 35}
                      (search-counts)))
    (is (= {:projects 35 :users 10 :orgs 35}
           (search-counts)))
    ;; search for bar
    (search-for "bar")
    (b/wait-until #(= {:projects 15 :users 0 :orgs 15}
                      (search-counts)))
    ;; do we have 15 projects, 0 users and 15 orgs?
    (is (= {:projects 15 :users 0 :orgs 15}
           (search-counts))))
  :cleanup (do
             (delete-projects-matching-term "foo")
             (delete-users-matching-term "foo")
             (delete-orgs-matching-term "foo")))
