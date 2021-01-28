(ns sysrev.test.browser.project-users
  (:require [clojure.test :refer [use-fixtures is]]
            [clojure.tools.logging :as log]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.core :refer [default-fixture]]
            [sysrev.config :refer [env]]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def valid-gengroups [{:name "English" :description "Group for the English language"}
                      {:name "Spanish" :description "Group for the Spanish language"}
                      {:name "Japanese" :description "Group for the Japanese language"}])

(defn create-gengroup [gengroup]
  (let [new-gengroup-btn "#new-gengroup-btn"
        group-name-input "#gengroup-name-input"
        group-description-input "#gengroup-description-input"
        create-gengroup-button "#create-gengroup-btn"
        success-notification ".ui.toast.success"]
    (b/wait-until-displayed new-gengroup-btn)
    (b/click new-gengroup-btn)
    (b/wait-until-displayed group-name-input)
    (b/set-input-text group-name-input (:name gengroup) :delay 50)
    (b/set-input-text group-description-input (:description gengroup) :delay 50)
    (b/click create-gengroup-button)
    (b/wait-until-displayed success-notification)
    (b/click success-notification :delay 100)))

(defn edit-gengroup [gengroup new-name new-description]
  (let [edit-gengroup-btn (format ".edit-gengroup-btn[data-gengroup-name='%s']" (:name gengroup))
        group-name-input "#gengroup-name-input"
        group-description-input "#gengroup-description-input"
        save-gengroup-button "#save-gengroup-btn"
        success-notification ".ui.toast.success"]
    (b/wait-until-displayed edit-gengroup-btn)
    (b/click edit-gengroup-btn)
    (b/wait-until-displayed group-name-input)
    (b/set-input-text group-name-input new-name :delay 50)
    (b/set-input-text group-description-input new-description :delay 50)
    (b/click save-gengroup-button)
    (b/wait-until-displayed success-notification)
    (b/click success-notification :delay 100)))

(defn delete-gengroup [gengroup]
  (let [delete-gengroup-btn (format ".delete-gengroup-btn[data-gengroup-name='%s']" (:name gengroup))
        delete-gengroup-confirmation-btn "#delete-gengroup-confirmation-btn"
        success-notification ".ui.toast.success"]
    (b/click delete-gengroup-btn)
    (b/wait-until-displayed delete-gengroup-confirmation-btn)
    (b/click delete-gengroup-confirmation-btn)
    (b/wait-until-displayed success-notification)
    (b/click success-notification :delay 100)))

(deftest-browser test-gengroups-crud
  true test-user
  [test-user (if (= (:profile env) :dev)
               (b/create-test-user)
               test-user)
   gengroup-to-edit (first valid-gengroups)
   gengroup-to-delete (second valid-gengroups)]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Project Users Test")
      (pm/import-pubmed-search-via-db "foo bar")
      (b/click "#project a.item.users")
      (b/wait-until-loading-completes :pre-wait 50 :loop 2)
      (doall
        (for [gengroup valid-gengroups]
          (create-gengroup gengroup)))
      (edit-gengroup gengroup-to-edit (str (:name gengroup-to-edit) " - edit") (str (:description gengroup-to-edit) " - edit"))
      (delete-gengroup gengroup-to-delete))
  :cleanup (do
             (nav/delete-current-project)
             (nav/log-out)))

(deftest-browser test-gengroups-assign
  true test-user
  [test-user (if (= (:profile env) :dev)
               (b/create-test-user)
               test-user)
   success-notification ".ui.toast.success"
   gengroup (first valid-gengroups)
   manage-member-btn ".manage-member-btn"
   gengroup-search-result (format ".result[name='%s']" (:name gengroup))
   search-gengroups-input "#search-gengroups-input"
   add-gengroup-btn "#add-gengroup-btn"]
  (do (nav/log-in (:email test-user))
      (nav/new-project "Project Users Test")
      (pm/import-pubmed-search-via-db "foo bar")
      (b/click "#project a.item.users")
      (b/wait-until-loading-completes :pre-wait 50 :loop 2)
      (create-gengroup gengroup)
      (b/click manage-member-btn)
      (b/set-input-text-per-char search-gengroups-input (:name gengroup) :delay 50)
      (b/click gengroup-search-result)
      (b/click add-gengroup-btn)
      (b/wait-until-displayed success-notification)
      (b/click success-notification :delay 100))
  :cleanup (do
             (nav/delete-current-project)
             (nav/log-out)))





