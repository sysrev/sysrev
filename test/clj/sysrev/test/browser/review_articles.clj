(ns sysrev.test.browser.review-articles
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.stripe :as stripe]
            [sysrev.test.core :as test :refer [wait-until]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [clojure.tools.logging :as log]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)
;; Note: There are different classes / ids / etc. for elements in mobile versus desktop
;;       If you are having an issue with not finding elements that should be there,
;;       try resizing the window to desktop to see if there is a discrepancy and then fix it
;;       in the element xpath names.
;;       You should have consistent test behavior wether you are in mobile or desktop!!!
;;
;; helpful manual testing functions:
;; There are additional notes in create_project.clj
;; (b/delete-test-user)

;; find the project
;; (users/user-self-info (:user-id (users/get-user-by-email (:email b/test-login))))

;; delete the project
;; (let [project-ids (->> (users/get-user-by-email (:email b/test-login)) :user-id users/user-self-info :projects (mapv :project-id) (filterv #(not= % 100)))] (mapv #(project/delete-project %) project-ids))

;; useful definitions after basic values have been set by tests
;; (def email (:email b/test-login))
;; (def password (:password b/test-login))
;; (def user-id (:user-id (users/get-user-by-email email)))
;; (def project-id (get-user-project-id user-id))
;; (def article-title (-> (labels/query-public-article-labels project-id) vals first :title))
;; (def article-id (-> (labels/query-public-article-labels project-id) keys first))

;;;; begin dom element definitions
(def review-articles-button
  (xpath "//span[text()='Review']"))
(def articles-button
  (xpath "//span[text()='Articles']"))
(def save-button
  (xpath "//button[contains(text(),'Save')]"))
(def disabled-save-button
  (xpath "//button[contains(@class,'disabled') and contains(text(),'Save')]"))
(def discard-button
  (xpath "//button[contains(@class,'labeled') and contains(text(),'Discard')]"))
(def label-definitions-tab
  (xpath "//span[contains(text(),'Label Definitions')]"))
;; create new labels buttons
(def add-boolean-label-button
  (xpath "//button[contains(text(),'Add Boolean Label')]"))
(def add-string-label-button
  (xpath "//button[contains(text(),'Add String Label')]"))
(def add-categorical-label-button
  (xpath "//button[contains(text(),'Add Categorical Label')]"))
;; editing label inputs
(def display-label-input
  (xpath "//label[contains(text(),'Display Name')]"
         "/descendant::input[@type='text']"))
(def must-be-answered-input
  (xpath "//label[contains(text(),'Must be answered?')]"
         "/descendant::input[@type='radio']"))
(def question-input
  (xpath "//label[contains(text(),'Question')]"
         "/descendant::input[@type='text']"))
(def label-item-div-with-errors
  (xpath "//div[contains(@class,'error')]"
         "/ancestor::div[contains(@class,'label-item')]"))
(def no-articles-need-review
  (xpath "//h4[text()='No articles found needing review']"))
(defn label-div-with-name
  [name]
  (xpath "//span[contains(@class,'name')]"
         "/span[contains(@class,'inner') and text()='" name "']"
         "/ancestor::div[contains(@class,'label-edit')"
         " and contains(@class,'column')]"))

(defn select-boolean-with-label-name
  "Use boolean select? to set the value of label with name.
  select? being true corresponds to 'Yes', false corresponds to 'No'"
  [name select?]
  (b/click
   (xpath (label-div-with-name name)
          "/descendant::div[contains(text(),'"
          (if select? "Yes" "No") "')]"))
  (Thread/sleep 25))

(defn input-string-with-label-name
  "Input string into label with name"
  [name string]
  (b/set-input-text
   (xpath (label-div-with-name name)
          "/descendant::input[@type='text']")
   string :delay 50))

(defn select-with-text-label-name
  "select text from the dropdown for label with name"
  [name text]
  (let [dropdown-div (xpath (label-div-with-name name)
                            "/descendant::div[contains(@class,'dropdown')]")
        entry-div (xpath (label-div-with-name name)
                         "/descendant::div[contains(text(),'" text "')]")]
    (b/click dropdown-div :displayed? true)
    (Thread/sleep 400)
    (b/click entry-div :displayed? true)
    (Thread/sleep 200)))

(defn article-title-div
  [title]
  (xpath "//span[contains(@class,'article-title') and contains(text(),'"
         title "')]"))

(defn label-button-value
  [label]
  (taxi/text
   (xpath "//div[contains(@class,'button') and contains(text(),'"
          label "')]"
          "/parent::div[contains(@class,'label-answer-tag')]"
          "/div[contains(@class,'label')]")))

(defn label-name-xpath
  "Given a label-name, return the xpath for it"
  [label-name]
  (xpath "label[contains(text(),'" label-name "')]"))

(defn get-label-error-message
  "Get the error message associated with a displayed name of label-name"
  [label-name]
  (taxi/text
   (xpath "//" (label-name-xpath label-name)
          "/parent::div"
          "/descendant::div[contains(@class,'message') and contains(@class,'red')]")))

(defn get-all-error-messages
  "Get all error messages"
  []
  (->> (taxi/find-elements
        (xpath "//div[contains(@class,'error')]"
               "/descendant::div[contains(@class,'message')"
               " and contains(@class,'red')]"))
       (mapv taxi/text)))

;;;; end element definitions

;;;; label definitions
(def include-label-definition {:value-type "boolean"
                               :short-label "Include"})

(def boolean-label-definition {:value-type "boolean"
                               :short-label "Boolean Label"
                               :question "Is this true or false?"
                               :definition {:inclusion-values [true]}
                               :required true})

(def string-label-definition {:value-type "string"
                              :short-label "String Label"
                              :question "What value is present for Foo?"
                              :definition
                              {:max-length 160
                               :examples ["foo" "bar" "baz" "qux"]
                               :multi? true}
                              :required true})

(def categorical-label-definition {:value-type "categorical"
                                   :short-label "Categorical Label"
                                   :question "Does this label fit within the categories?"
                                   :definition
                                   {:all-values ["Foo" "Bar" "Baz" "Qux"]
                                    :inclusion-values ["Foo" "Bar"]
                                    :multi? false}
                                   :required true})
(defn short-label-answer
  "Get label answer for short-label set for article-id in project-id by user-id"
  [project-id article-id user-id short-label]
  (let [label-uuid (->> (project/project-labels project-id)
                        vals
                        (filter #(= short-label (:short-label %)))
                        first
                        :label-id)]
    (-> (labels/article-user-labels-map project-id article-id)
        (get user-id)
        (get label-uuid)
        :answer)))

(defn get-user-project-id
  "Return the first project-id of user-id"
  [user-id]
  (-> user-id users/user-self-info :projects first :project-id))

(defn delete-label
  "Delete the label-item described by xpath string"
  [xpath]
  (let [div-id (taxi/attribute (x/xpath xpath) :id)]
    (b/click
     (x/xpath "//div[@id='" div-id "']"
              "/descendant::div[contains(@class,'remove')]"))))

(defn click-edit
  "Click the Edit button on a label describe by a string xpath"
  [xpath]
  (b/click (x/xpath xpath "/descendant::i[contains(@class,'edit')]")))

(defn save-label []
  (log/info "saving label definition")
  (b/click save-button :delay 50))

(defn discard-label []
  #_ (log/info "discarding label definition")
  (b/click discard-button :delay 50))

(defn label-text-input-xpath
  "Given an xpath, get the text input for label-name under xpath"
  [xpath label-name]
  (x/xpath xpath
           "/descendant::" (label-name-xpath label-name)
           "/parent::div/descendant::input[@type='text']"))

(defn label-radio-input-xpath
  "Given an xpath, get the radio button for label-name under xpath"
  [xpath label-name]
  (x/xpath xpath
           "/descendant::" (label-name-xpath label-name)
           "/parent::div/descendant::input[@type='radio']"))

(defn set-radio-button
  "When selected? is true, set radio input defined by xpath to 'on',
  otherwise if selected? is false, set radio input to 'off'"
  [xpath selected?]
  (when-not (= selected? (taxi/selected? (x/xpath xpath)))
    (b/click (x/xpath xpath))))

(defn label-checkbox-input-xpath
  "Given an xpath, get the check box for label-name under xpath"
  [xpath label-name]
  (x/xpath xpath
           "/descendant::" (label-name-xpath label-name)
           "/parent::div/descendant::input[@type='checkbox']"))

(defn set-checkbox-button
  "When selected? is true, set checkbox defined by xpath to 'on',
  otherwise if selected? is false, set checkbox button to 'off'"
  [xpath selected?]
  (when-not (= selected? (taxi/selected? (x/xpath xpath)))
    (b/click (x/xpath xpath))))

(defn value-for-inclusion-checkbox
  [xpath inclusion-value]
  (x/xpath xpath
           "/descendant::" (label-name-xpath "for Inclusion")
           "/parent::div/"
           "descendant::label[contains(text(),'" inclusion-value "')]"
           "/parent::div[contains(@class,'checkbox')]/input[@type='checkbox']"))

(defn set-boolean-inclusion
  "When include? is true, set check box to 'Yes', when false, set to 'No'"
  [xpath include?]
  (let [checkbox (fn [status]
                   (value-for-inclusion-checkbox
                    xpath (if status "Yes" "No")))]
    (cond (and include? (taxi/selected? (checkbox (not include?))))
          (b/click (checkbox include?))
          (and (not include?) (taxi/selected? (checkbox (not include?))))
          (b/click (checkbox include?)))))

(defn set-boolean-label-values
  [xpath label-map]
  (let [{:keys [question short-label required value-type
                definition]
         :or {question ""
              short-label ""
              required false
              value-type "boolean"}} label-map
        {:keys [inclusion-values]
         :or {inclusion-values [true]}} definition]
    ;; Enter the display name
    (b/set-input-text
     (label-text-input-xpath xpath "Display Name")
     short-label)
    ;; enter the question
    (b/set-input-text
     (label-text-input-xpath xpath "Question")
     question)
    ;; required setting
    (set-checkbox-button
     (label-checkbox-input-xpath xpath "Must be answered?") required)
    ;; inclusion values
    (set-boolean-inclusion xpath (first inclusion-values))))

(defn set-string-label-values
  [xpath label-map]
  (let [{:keys [question short-label required value-type
                definition]
         :or {question ""
              short-label ""
              required false
              value-type "string"}} label-map
        {:keys [examples max-length multi?]
         :or {examples []
              max-length ""}} definition]
    ;; Enter the display name
    (b/set-input-text
     (label-text-input-xpath xpath "Display Name")
     short-label)
    ;; required setting
    (set-checkbox-button
     (label-checkbox-input-xpath xpath "Must be answered?") required)
    ;; allow multiple values?
    (set-checkbox-button
     (label-checkbox-input-xpath xpath "Allow multiple values?") multi?)
    ;; enter the question
    (b/set-input-text
     (label-text-input-xpath xpath "Question")
     question)
    ;; enter the max length
    (b/set-input-text
     (label-text-input-xpath xpath "Max Length")
     (str max-length))
    ;; Examples
    (b/set-input-text
     (label-text-input-xpath xpath "Examples (comma separated)")
     (str/join "," examples)
     :delay 50)))

(defn set-categorical-label-values
  [xpath label-map]
  (let [{:keys [question short-label required value-type
                definition]
         :or {question ""
              short-label ""
              required false
              value-type "categorical"}} label-map
        {:keys [multi? all-values inclusion-values]
         :or {all-values []
              inclusion-values []}} definition]
    ;; Enter the display name
    (b/set-input-text
     (label-text-input-xpath xpath "Display Name")
     short-label)
    ;; required setting
    (set-checkbox-button
     (label-checkbox-input-xpath xpath "Must be answered?") required)
    ;; enter the question
    (b/set-input-text
     (label-text-input-xpath xpath "Question")
     question)
    ;; enter the categories
    (b/set-input-text
     (label-text-input-xpath xpath "Categories (comma separated options)")
     (str/join "," all-values)
     :delay 50)
    ;;  inclusion values
    (taxi/wait-until
     #(= (taxi/value (label-text-input-xpath
                      xpath "Categories (comma separated options)"))
         (str/join "," all-values))
     5000 25)
    ;; set the inclusion values
    (mapv #(let [inclusion-checkbox (value-for-inclusion-checkbox xpath %)
                 included? (contains? (set inclusion-values)
                                      %)]
             ;; each time a selection is made, the checkboxes
             ;; are re-rendered. Need to make sure it is present
             ;; before setting inclusion value
             (b/wait-until-exists inclusion-checkbox)
             (when (not= (taxi/selected? inclusion-checkbox)
                         included?)
               (b/click inclusion-checkbox)))
          all-values)))

(defn set-label-values
  "Given a label map, set the values accordingly in the browser"
  [xpath label-map]
  (let [{:keys [value-type]
         :or {value-type "boolean"}} label-map]
    (b/wait-until-displayed (x/xpath xpath))
    (condp = value-type
      "boolean"     (set-boolean-label-values xpath label-map)
      "string"      (set-string-label-values xpath label-map)
      "categorical" (set-categorical-label-values xpath label-map))))

(defn set-article-label
  "Set the labels for article using the values in the map
  {:short-label <string> :value <boolean|string> :value-type <string>}"
  [{:keys [short-label value value-type]}]
  (condp = value-type
    "boolean" (select-boolean-with-label-name
               short-label
               value)
    "string" (input-string-with-label-name
              short-label
              value)
    "categorical" (select-with-text-label-name
                   short-label
                   value))
  (Thread/sleep 50))

(defn set-article-labels
  "Given a vector of label-settings maps, set the labels for an article in the browser."
  [label-settings]
  (log/info "setting article labels")
  (when (taxi/exists? x/review-labels-tab)
    (b/click x/review-labels-tab)
    (Thread/sleep 50))
  (mapv #(set-article-label %) label-settings)
  (Thread/sleep 100)
  (b/click save-button)
  (b/wait-until-loading-completes :pre-wait 200))

(defn randomly-set-article-labels
  "Given a vector of label-settings maps, set the labels for an article in the browser, randomly choosing from a set of values in {:definition {:all-values} or {:definition {:examples} ."
  [label-settings]
  (set-article-labels
   (mapv (fn [label]
           (let [all-values (or (get-in label [:definition :all-values])
                                (get-in label [:definition :examples]))]
             (merge label {:value
                           (nth all-values (rand-int (count all-values)))})))
         label-settings)))

(let [project-id (atom nil)]
  (deftest-browser create-project-and-review-article
    (when (test/db-connected?)
      (let [project-name "Sysrev Browser Test"
            search-term-first "foo bar"
            no-display "Display name must be provided"
            no-question "Question text must be provided"
            no-max-length "Max length must be provided"
            no-options "Category options must be provided"
            have-errors? #(= (set (get-all-error-messages))
                             (set %))
            {:keys [user-id]} (users/get-user-by-email (:email b/test-login))
            _ (nav/log-in)
            _ (nav/new-project project-name)
            new-label "//div[contains(@id,'new-label-')]"]
        (reset! project-id (b/current-project-id))
        (assert (integer? @project-id))

        (nav/go-project-route "/add-articles")
        (pm/add-articles-from-search-term search-term-first)

;;; create new labels
        (let [include-label-value true
              boolean-label-value true
              string-label-value "Baz"
              categorical-label-value "Qux"]
          (log/info "creating label definitions")
          (nav/go-project-route "/labels/edit")
          ;; create a new boolean label
          (b/click add-boolean-label-button)
          (save-label)
          ;; there should be some errors
          (taxi/wait-until #(have-errors? [no-display no-question])
                           5000 25)
          (is (have-errors? [no-display no-question]))
          (discard-label)
          ;; change the type of the label to string, check error messages
          (b/click add-string-label-button)
          (save-label)
          (taxi/wait-until #(have-errors? [no-display no-question no-max-length])
                           5000 25)
          (is (have-errors? [no-display no-question no-max-length]))
          (discard-label)
          ;; change the type of the label to categorical, check error message
          (b/click add-categorical-label-button)
          (save-label)
          (taxi/wait-until #(have-errors? [no-display no-question no-options])
                           5000 25)
          (is (have-errors? [no-display no-question no-options]))
          (discard-label)
          ;; create a boolean label
          #_ (do (b/click add-boolean-label-button)
                 (set-label-values new-label boolean-label-definition)
                 (save-label)
                 ;; there is a new boolean label
                 (is (b/exists? (x/match-text
                                 "span" (:short-label boolean-label-definition)))))

          ;; create a string label
          (b/click add-string-label-button)
          (set-label-values new-label string-label-definition)
          (save-label)
          ;; there is a new string label
          (is (b/exists? (x/match-text
                          "span" (:short-label string-label-definition))))
          ;; create a categorical label
          (b/click add-categorical-label-button)
          (set-label-values new-label categorical-label-definition)
          (save-label)
          ;; there is a new categorical label
          (is (b/exists? (x/match-text
                          "span" (:short-label categorical-label-definition))))
;;;; review an article
          (nav/go-project-route "")
          (b/click review-articles-button :delay 50)
          (b/click x/enable-sidebar-button
                   :if-not-exists :skip :delay 100)
          (b/click x/review-labels-tab)
          (b/wait-until-displayed
           (label-div-with-name (:short-label include-label-definition)))
          ;; We shouldn't have any labels for this project
          (is (empty? (labels/query-public-article-labels @project-id)))
          ;; set the labels
          (set-article-labels [(merge include-label-definition
                                      {:value include-label-value})
                               #_ (merge boolean-label-definition
                                         {:value boolean-label-value})
                               (merge string-label-definition
                                      {:value string-label-value})
                               (merge categorical-label-definition
                                      {:value categorical-label-value})])
          ;;verify we are on the next article
          (is (b/exists? disabled-save-button))
;;;; check in the database for the labels
          ;; we have labels for just one article
          (is (= 1 (count (labels/query-public-article-labels @project-id))))
          (log/info "checking label values from db")
          (let [ ;; this is not yet generalized
                article-id (-> (labels/query-public-article-labels
                                @project-id) keys first)
                article-title (-> (labels/query-public-article-labels
                                   @project-id) vals first :title)]
            ;; these are just checks in the database
            (is (= include-label-value
                   (short-label-answer @project-id article-id user-id
                                       (:short-label include-label-definition))))
            #_ (is (= boolean-label-value
                      (short-label-answer @project-id article-id user-id
                                          (:short-label boolean-label-definition))))
            (is (= string-label-value
                   (-> (short-label-answer @project-id article-id user-id
                                           (:short-label string-label-definition))
                       first)))
            (is (= categorical-label-value
                   (-> (short-label-answer @project-id article-id user-id
                                           (:short-label categorical-label-definition))
                       first)))
            (log/info "checking label values from editor")
;;;; Let's check the actual UI for this
            (b/click articles-button)
            (b/click (article-title-div article-title))
            (b/wait-until-displayed
             (xpath "//div[contains(@class,'button') and contains(text(),'Change Labels')]"))
            ;; check overall include
            ;; note: booleans value name have ? appended to them
            (is (= include-label-value
                   (-> (label-button-value (str (:short-label include-label-definition) "?"))
                       read-string
                       boolean)))
            ;; check a boolean value
            #_ (is (= boolean-label-value
                      (-> (label-button-value (str (:short-label boolean-label-definition) "?"))
                          read-string
                          boolean)))
            ;; check a string value
            (is (= string-label-value
                   (label-button-value (:short-label string-label-definition))))
            ;; check a categorical value
            (is (= categorical-label-value
                   (label-button-value (:short-label categorical-label-definition))))))))
    :cleanup (when (test/db-connected?)
               (when @project-id (project/delete-project @project-id)))))

(defn randomly-review-all-articles
  "Randomly sets labels for articles until all have been reviewed"
  [label-definitions]
  (b/click review-articles-button)
  (b/wait-until-exists {:xpath "//div[@id='project_review']"})
  (while (not (taxi/exists? no-articles-need-review))
    (do (randomly-set-article-labels label-definitions)
        (taxi/wait-until #(or (taxi/exists? disabled-save-button)
                              (taxi/exists? no-articles-need-review))
                         5000 50))))

(defn randomly-review-n-articles
  "Randomly sets labels for n articles using a vector of label-definitions"
  [n label-definitions]
  (b/click review-articles-button)
  (b/wait-until-exists {:xpath "//div[@id='project_review']"})
  (dotimes [i n]
    (do
      (randomly-set-article-labels label-definitions)
      (taxi/wait-until #(or (taxi/exists? disabled-save-button)
                            (taxi/exists? no-articles-need-review))
                       5000 50))))

;; (randomly-review-all-articles [(merge include-label-definition {:all-values [true false]})
;; (randomly-review-n-articles 15 [(merge include-label-definition {:all-values [true false]})])
