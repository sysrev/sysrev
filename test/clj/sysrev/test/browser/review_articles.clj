(ns sysrev.test.browser.review-articles
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.test :refer :all]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.stripe :as stripe]
            [sysrev.test.core :refer [default-fixture wait-until delete-user-fixture]]
            [sysrev.test.browser.core :as browser]
            [sysrev.test.browser.navigate :as navigate]
            [sysrev.test.browser.create-project :as create-project]))

(def email "foo@bar.com")
(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each (delete-user-fixture email))
;; helpful manual testing functions:
;; (do (stripe/unsubscribe-customer! (users/get-user-by-email "foo@bar.com")) (stripe/delete-customer! (users/get-user-by-email "foo@bar.com")) (users/delete-user (:user-id (users/get-user-by-email "foo@bar.com"))))

;; delete the project after it has been reviewed
;; find the project
;; (users/user-self-info (:user-id (users/get-user-by-email email)))
;; delete the project
;; (let [project-id (-> (users/get-user-by-email email) :user-id users/user-self-info :projects first :project-id)] (project/delete-project project-id))

;; useful definitions after basic values have been set by tests
;; (def email "foo@bar.com")
;; (def user-id (:user-id (users/get-user-by-email email)))
;; (def project-id (get-user-project-id user-id))
;; (def article-title (-> (labels/query-public-article-labels project-id) vals first :title))
;; (def article-id (-> (labels/query-public-article-labels project-id) keys first))

(def include-label-server-name "overall include")
(def review-articles-button
  {:xpath "//span[text()='Review Articles']"})
(def articles-button
  {:xpath "//span[text()='Articles']"})

(defn label-div-with-name
  [name]
  (str "//span[contains(@class,'name')]/span[contains(text(),'" name "')]/ancestor::div[contains(@class,'label-edit') and contains(@class,'column')]"))

(defn select-boolean-with-label-name
  "Use boolean select? to set the value of label with name.
  select? being true corresponds to 'Yes', false corresponds to 'No'"
  [name select?]
  (taxi/click
   {:xpath (str (label-div-with-name name) "/descendant::div[contains(text(),'" (if select? "Yes" "No") "')]")}))

(defn input-string-with-label-name
  "Input string into label with name"
  [name string]
  (let [label-input {:xpath (str (label-div-with-name name) "/descendant::input[@type='text']")}]
    (taxi/clear label-input)
    (taxi/input-text label-input string)))

(defn select-with-text-label-name
  "select text from the dropdown for label with name"
  [name text]
  (let [label-div (label-div-with-name name)
        label-text-div (str (label-div-with-name name) "/descendant::div[contains(text(),'" text "')]")]
    (taxi/click {:xpath (str (label-div-with-name name) "/descendant::div[contains(@class,'dropdown')]")})
    ;; wait for it to be available
    (browser/wait-until-displayed {:xpath label-text-div})
    (taxi/click {:xpath label-text-div})))

(def save-button {:xpath "//div[contains(text(),'Save')]"})
(def disabled-save-button {:xpath "//div[contains(text(),'Save') and contains(@class,'disabled')]"})

(defn article-title-div
  [title]
  (str "//span[contains(@class,'article-title') and contains(text(),'"
       title"')]"))

(defn label-button-value
  [label]
  (taxi/text {:xpath (str "//div[contains(@class,'button') and contains(text(),'"
                          label "')]/parent::div[contains(@class,'label-answer-tag')]/div[contains(@class,'label')]")}))

(defn create-label-id-name-map
  [project-id]
  "Given a project-id, return a map of the form
  {<name> <uuid>}
  where <uuid> is the label-id"
  (apply merge (map #(hash-map (:name %) (:label-id %)) (vals (project/project-labels project-id)))))

(defn label-name->label-uuid
  [label-id-name-map label-name]
  (get label-id-name-map label-name))

(defn get-label-values-for-article
  "Get labels values for article-id in project-id as set by user-id with label-name"
  [project-id article-id user-id label-name]
  (get (get (labels/article-user-labels-map project-id article-id)
            user-id)
       ;; label name map to get label-uuid
       (label-name->label-uuid
        (create-label-id-name-map project-id)
        label-name)))

(defn get-user-project-id
  "Return the first project-id of user-id"
  [user-id]
  (-> user-id users/user-self-info :projects first :project-id))

(deftest create-project-and-review-article
  (let [password "foobar"
        project-name "Foo Bar"
        search-term-first "foo bar"]
    ;; register the user
    (navigate/register-user email password)
    (browser/wait-until-loading-completes)
;;; create a project
    (browser/go-route "/select-project")
    (browser/wait-until-loading-completes)
    (taxi/input-text {:xpath "//input[@placeholder='Project Name']"} project-name)
    (taxi/click {:xpath "//button[text()='Create']"})
    (browser/wait-until-displayed create-project/project-title-xpath)
    (let [user-id (:user-id (users/get-user-by-email email))
          project-id (get-user-project-id user-id)]
      ;; was the project actually created?
      (is (.contains (taxi/text create-project/project-title-xpath) project-name))
      (browser/go-route "/project/add-articles")
;;;; add sources
      ;; create a new source
      (create-project/add-articles-from-search-term search-term-first)
      ;; check that there is one article source listed
      (taxi/wait-until #(= 1 (count (taxi/find-elements create-project/article-sources-list-xpath))))

;;;; create new labels
      ;; for now, this is manually in the db, eventually this whole
      ;; section will be replaced by what is in the label editor
      (let [foo-label-definition {:name "Foo Boolean"
                                  :question "Is Foo true or false?"
                                  :short-label "Foo"
                                  :inclusion-value true :required true}
            bar-label-definition {:name "Bar String"
                                  :question "Does this have any bar?"
                                  :short-label "Bar"
                                  :max-length 160
                                  :entity "Corges"
                                  :examples '("foo" "bar" "baz" "qux")
                                  :multi? false
                                  :required false}
            baz-label-definition {:name "Baz Categorical"
                                  :question "Does Baz fit within the categories?"
                                  :required false
                                  :short-label "Baz"
                                  :all-values ["Foo" "Bar" "Baz" "Qux"]
                                  :inclusion-values ["Foo" "Bar"]
                                  :multi? false}
            ;; set values to the following labels
            include-label-name "Include"
            include-label-value true
            foo-label-name "Foo"
            foo-label-value true
            bar-label-name "Bar"
            bar-label-value "Baz"
            baz-label-name "Baz"
            baz-label-value "Qux"]
        ;; create a boolean label
        (labels/add-label-entry-boolean project-id
                                        foo-label-definition)
        ;; create a string label
        (labels/add-label-entry-string project-id
                                       bar-label-definition)
        ;; create a categorical label
        (labels/add-label-entry-categorical project-id
                                            baz-label-definition)
;;;; review an article
        (browser/wait-until-loading-completes)
        (browser/wait-until-displayed review-articles-button)
        (taxi/click review-articles-button)
        (browser/wait-until-displayed {:xpath (label-div-with-name include-label-name)})
        ;; We shouldn't have any labels for this project
        (is (empty? (labels/query-public-article-labels project-id)))
        ;; Check the booleans
        (select-boolean-with-label-name include-label-name include-label-value)
        (select-boolean-with-label-name foo-label-name foo-label-value)
        ;; Input string
        (input-string-with-label-name bar-label-name bar-label-value)
        ;; make a selection
        (select-with-text-label-name baz-label-name baz-label-value)
        ;; save the labeling
        (taxi/click save-button)
        ;; verify we are on the next article
        (browser/wait-until-displayed disabled-save-button)
        (is (taxi/exists? disabled-save-button))
;;;; check in the database for the labels
        ;; we have labels for just one article
        (is (= 1
               (count (labels/query-public-article-labels project-id))))
        (let [ ;; this is not yet generalized
              article-id (-> (labels/query-public-article-labels
                              project-id) keys first)
              article-title (-> (labels/query-public-article-labels
                                 project-id) vals first :title)]
          ;; these are just checks in the database
          (is (= include-label-value
                 (:answer (get-label-values-for-article project-id article-id user-id include-label-server-name))))
          (is (= foo-label-value
                 (:answer (get-label-values-for-article project-id article-id user-id "Foo Boolean"))))
          (is (= bar-label-value
                 (-> (get-label-values-for-article project-id article-id user-id "Bar String")
                     :answer first)))
          (is (= baz-label-value
                 (-> (get-label-values-for-article project-id article-id user-id "Baz Categorical") :answer first)))
;;;; Let's check the actual UI for this
          (taxi/click articles-button)
          (browser/wait-until-displayed {:xpath (article-title-div article-title)})
          (taxi/click {:xpath (article-title-div article-title)})
          (browser/wait-until-displayed {:xpath "//div[contains(@class,'button') and contains(text(),'Change Labels')]"})
          ;; check overall include
          ;; note: booleans value name have ? appended to them
          (is (= include-label-value
                 (-> (label-button-value (str include-label-name "?"))
                     read-string
                     boolean)))
          ;; check a boolean value
          (is (= foo-label-value
                 (-> (label-button-value (str foo-label-name "?"))
                     read-string
                     boolean)))
          ;; check a string value
          (is (= bar-label-value
                 (label-button-value bar-label-name)))
          ;; check a categorical value
          (is (= baz-label-value
                 (label-button-value baz-label-name))))
        ;; cleanup
        (navigate/log-out)
        ;; delete the project in the database
        (project/delete-project project-id)))))
