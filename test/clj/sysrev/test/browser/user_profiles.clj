(ns sysrev.test.browser.user_profiles
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [sysrev.db.core :refer [with-transaction]]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.test.browser.annotator :as annotator]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.review-articles :as ra]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def user-name-link (xpath "//a[@id='user-name-link']"))
(def user-profile-tab (xpath "//a[@id='user-profile']"))
(def public-project-divs (xpath "//div[@id='public-projects']/div[contains(@id,'project-')]/a"))
(def private-project-divs (xpath "//div[@id='private-projects']/div[contains(@id,'project-')]/a"))
(def activity-values (xpath "//h2[contains(@class,'articles-reviewed' | 'labels-contributed' | 'annotations-contributed')]"))
(def user-activity-summary-div (xpath "//div[@id='user-activity-summary']" activity-values))
(defn project-activity-summary-div [project-name] (xpath "//a[contains(text(),'" project-name "')]"
                                                         "/ancestor::div[contains(@id,'project-')]"
                                                         activity-values))
(defn private-project-names
  []
  (b/wait-until-displayed private-project-divs)
  (->> private-project-divs
       taxi/find-elements
       (mapv taxi/text)))

(defn public-project-names
  []
  (b/wait-until-displayed public-project-divs)
  (->> public-project-divs
       taxi/find-elements
       (mapv taxi/text)))

(defn user-activity-summary
  []
  (b/wait-until-displayed user-activity-summary-div)
  (->> user-activity-summary-div
       taxi/find-elements
       (mapv #(hash-map (keyword (taxi/attribute % :class)) (read-string (taxi/text %))))
       (apply merge)))

(defn project-activity-summary
  [project-name]
  (b/wait-until-displayed (project-activity-summary-div project-name))
  (->> (project-activity-summary-div project-name)
       taxi/find-elements
       (mapv #(hash-map (keyword (taxi/attribute % :class)) (read-string (taxi/text %))))
       (apply merge)))

(defn make-public-reviewer
  [user-id email]
  (with-transaction
    (users/create-email-verification! user-id email)
    (users/verify-email! email (:verify-code (users/read-email-verification-code user-id email)) user-id)
    (users/set-primary-email! user-id email)))

(deftest-browser correct-project-activity
  (test/db-connected?)
  [project-name-1 "Sysrev Browser Test (correct-project-activity 1)"
   project-name-2 "Sysrev Browser Test (correct-project-activity 2)"
   search-term "foo bar"
   email (:email b/test-login)
   user-id (-> (:email b/test-login)
               users/get-user-by-email
               :user-id)]
  (do (nav/log-in)
      ;; create a project, populate with articles
      (nav/new-project project-name-1)
      (pm/add-articles-from-search-term search-term)
      ;; go to the user profile
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; is the project-name listed in the private projects section?
      (is (= project-name-1 (first (private-project-names))))
      ;; do some work to see if it shows up in the user profile
      (b/click (xpath "//a[contains(text(),'" project-name-1 "')]"))
      (b/click ra/review-articles-button :delay 50)
      ;; set three article labels
      (dotimes [n 3]
        (ra/set-article-answers [(merge ra/include-label-definition
                                                     {:value true})]))
      ;; go back to profile, check activity
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; is the user's overall activity correct?
      (is (= 3 (:articles-reviewed (user-activity-summary))))
      (is (= 3 (:labels-contributed (user-activity-summary))))
      ;; is the individual projects activity correct?
      (is (= 3 (:articles-reviewed (project-activity-summary project-name-1))))
      (is (= 3 (:labels-contributed (project-activity-summary project-name-1))))
      ;; let's do some annotations to see if they are showing up
      (b/click (xpath "//a[contains(text(),'" project-name-1 "')]"))
      (nav/go-project-route "/articles")
      (b/wait-until-loading-completes :pre-wait 200)
      (b/click annotator/article-title-div :delay 200)
      (annotator/annotate-article "foo" "bar" :offset-x 100)
      ;; return to the profile, the user should have one annotation
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; total activity
      (is (= 3 (:articles-reviewed (user-activity-summary))))
      (is (= 3 (:labels-contributed (user-activity-summary))))
      (is (= 1 (:annotations-contributed (user-activity-summary))))
      ;; project activity
      (is (= 3 (:articles-reviewed (project-activity-summary project-name-1))))
      (is (= 3 (:labels-contributed (project-activity-summary project-name-1))))
      (is (= 1 (:annotations-contributed (project-activity-summary project-name-1))))
      ;; add another project
      (nav/new-project project-name-2)
      (pm/add-articles-from-search-term search-term)
      ;; go to the profile
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; is the project-name listed in the private projects section?
      (is (= project-name-2 (->> (private-project-names)
                                 (filter #(= % project-name-2))
                                 first)))
            ;; do some work to see if it shows up in the user profile
      (b/click (xpath "//a[contains(text(),'" project-name-2 "')]"))
      (b/click ra/review-articles-button :delay 50)
      ;; set two article labels
      (dotimes [n 2]
        (ra/set-article-answers [(merge ra/include-label-definition
                                        {:value true})]))
      ;; annotate
      (nav/go-project-route "/articles")
      (b/wait-until-loading-completes :pre-wait 200)
      (b/click annotator/article-title-div :delay 200)
      (annotator/annotate-article "foo" "bar" :offset-x 100)
      ;; go back and check activity
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; total activity
      (is (= 5 (:articles-reviewed (user-activity-summary))))
      (is (= 5 (:labels-contributed (user-activity-summary))))
      (is (= 2 (:annotations-contributed (user-activity-summary))))
      ;; project-1
      (is (= 3 (:articles-reviewed (project-activity-summary project-name-1))))
      (is (= 3 (:labels-contributed (project-activity-summary project-name-1))))
      (is (= 1 (:annotations-contributed (project-activity-summary project-name-1))))
      ;; project-2
      (is (= 2 (:articles-reviewed (project-activity-summary project-name-2))))
      (is (= 2 (:labels-contributed (project-activity-summary project-name-2))))
      (is (= 1 (:annotations-contributed (project-activity-summary project-name-2))))
      ;; make the first project, check that both projects show up in the correct divs
      (b/click (xpath "//a[contains(text(),'" project-name-1 "')]"))
      (nav/go-project-route "/settings")
      (b/click (xpath "//button[@id='public-access_public']"))
      (b/click (xpath "//div[contains(@class,'project-options')]//button[contains(@class,'save-changes')]"))
      (b/click user-name-link)
      (b/click user-profile-tab)
      (taxi/wait-until #(= project-name-1 (first (public-project-names))))
      (is (= project-name-1 (first (public-project-names))))
      (taxi/wait-until #(= project-name-2 (first (private-project-names))))
      (is (= project-name-2 (first (private-project-names)))))
  :cleanup
  (do (->> (users/projects-member user-id) (mapv :project-id) (mapv project/delete-project))))
