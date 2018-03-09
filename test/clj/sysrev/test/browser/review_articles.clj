(ns sysrev.test.browser.review-articles
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.stripe :as stripe]
            [sysrev.test.core :refer [default-fixture wait-until]]
            [sysrev.test.browser.core :as browser]
            [sysrev.test.browser.navigate :as navigate]
            [sysrev.test.browser.create-project :as create-project]))

(use-fixtures :once default-fixture browser/webdriver-fixture-once)
(use-fixtures :each browser/webdriver-fixture-each)
;; helpful manual testing functions:
;; (browser/delete-test-user)

;; delete the project after it has been reviewed
;; find the project
;; (users/user-self-info (:user-id (users/get-user-by-email (:email browser/test-login))))

;; delete the project
;; (let [project-id (-> (users/get-user-by-email (:email browser/test-login)) :user-id users/user-self-info :projects first :project-id)] (project/delete-project project-id))

;; useful definitions after basic values have been set by tests
;; (def email "browser+test@insilica.co")
;; (def user-id (:user-id (users/get-user-by-email email)))
;; (def project-id (get-user-project-id user-id))
;; (def article-title (-> (labels/query-public-article-labels project-id) vals first :title))
;; (def article-id (-> (labels/query-public-article-labels project-id) keys first))

;;;; begin dom element defintions
(def review-articles-button  {:xpath "//span[text()='Review Articles']"})
(def articles-button  {:xpath "//span[text()='Articles']"})
(def save-button {:xpath "//div[contains(text(),'Save')]"})
(def disabled-save-button {:xpath "//div[contains(text(),'Save') and contains(@class,'disabled')]"})
(def label-definitions-tab {:xpath "//span[contains(text(),'Label Definitions')]"})
;; create new labels buttons
(def add-boolean-label-button {:xpath "//div[contains(text(),'Add Boolean Label')]"})
(def add-string-label-button {:xpath "//div[contains(text(),'Add String Label')]"})
(def add-categorical-label-button {:xpath "//div[contains(text(),'Add Categorical Label')]"})
;; editing label inputs
(def display-label-input {:xpath "//label[contains(text(),'Display Label')]/descendant::input[@type='text']"})
(def must-be-answered-input {:xpath "//label[contains(text(),'Must be answered?')]/descendant::input[@type='radio']"})
(def question-input {:xpath "//label[contains(text(),'Question')]/descendant::input[@type='text']"})
;; save / cancel labels buttons
(def save-labels-button {:xpath "//div[contains(@class,'button') and contains(text(),'Save Labels')]"})
(def cancel-button {:xpath "//div[contains(@class,'button') and contains(text(),'Cancel')]"})
;; a string xpath for a label item div with errors
(def label-item-div-with-errors "//div[contains(@class,'error')]/ancestor::div[contains(@class,'label-item')]")

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

(defn article-title-div
  [title]
  (str "//span[contains(@class,'article-title') and contains(text(),'"
       title"')]"))

(defn label-button-value
  [label]
  (taxi/text {:xpath (str "//div[contains(@class,'button') and contains(text(),'"
                          label "')]/parent::div[contains(@class,'label-answer-tag')]/div[contains(@class,'label')]")}))

(defn label-name-xpath
  "Given a label-name, return the xpath for it"
  [label-name]
  (str "label[contains(text(),'" label-name "')]"))

(defn get-label-error-message
  "Get the error message associated with a displayed name of label-name"
  [label-name]
  (taxi/text {:xpath (str "//" (label-name-xpath label-name) "/parent::div/descendant::div[contains(@class,'message') and contains(@class,'red')]")}))

(defn get-all-error-messages
  "Get all error messages"
  []
  (map taxi/text (taxi/find-elements {:xpath "//div[contains(@class,'error')]/descendant::div[contains(@class,'message') and contains(@class,'red')]"})))

(defn change-label-type
  "Change label to type"
  [xpath value-type]
  (taxi/select-option
   {:xpath (str xpath "//select[contains(@class,'ui dropdown')]")}
   {:value value-type}))


;;;; end element definitions
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

(defn click-x
  "Click the X on a label described by xpath string"
  [xpath]
  (taxi/click {:xpath xpath})
  (taxi/click {:xpath (str xpath "/descendant::i[contains(@class,'remove')]")}))

(defn delete-label
  "Delete the label-item described by xpath string"
  [xpath]
  (let [div-id (taxi/attribute {:xpath xpath} :id)
        div-xpath (str "//div[@id='" div-id"']")]
    (click-x div-xpath)
    (click-x div-xpath)))

(defn click-edit
  "Click the Edit button on a label describe by a string xpath"
  [xpath]
  (taxi/click {:xpath xpath})
  (taxi/click {:xpath (str xpath "/descendant::i[contains(@class,'edit')]")}))

(defn label-text-input-xpath
  "Given an xpath, get the text input for label-name under xpath"
  [xpath label-name]
  (str xpath "/descendant::" (label-name-xpath label-name) "/parent::div/descendant::input[@type='text']"))

(defn label-radio-input-xpath
  "Given an xpath, get the radio button for label-name user xpath"
  [xpath label-name]
  (str xpath "/descendant::" (label-name-xpath label-name) "/parent::div/descendant::input[@type='radio']"))

(defn set-radio-button
  "When selected? is true, set radio button defined by xpath to 'on',
  otherwise if selected? is false, set radio button to 'off'"
  [xpath selected?]
  (when-not (= (taxi/selected? {:xpath xpath})
               selected?)
    (taxi/click {:xpath xpath})))

(defn value-for-inclusion-checkbox
  [xpath inclusion-value]
  (str xpath "/descendant::"
       (label-name-xpath "for Inclusion")
       "/parent::div/"
       "descendant::label[contains(text(),'" inclusion-value "')]"
       "/parent::div[contains(@class,'checkbox')]/input[@type='checkbox']"))

(defn set-boolean-inclusion
  "When include? is true, set check box to 'Yes', when false, set to 'No'"
  [xpath include?]
  (let [checkbox (fn [bool?]
                   {:xpath
                    (value-for-inclusion-checkbox xpath (if bool?
                                                          "Yes"
                                                          "No"))})]
    (cond (and include?
               (taxi/selected? (checkbox (not include?))))
          (taxi/click (checkbox include?))
          (and (not include?)
               (taxi/selected? (checkbox (not include?))))
          (taxi/click (checkbox include?)))))

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
    ;; set the type
    (change-label-type xpath value-type)
    ;; Enter the display name
    (taxi/clear {:xpath (label-text-input-xpath xpath "Display Label")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Display Label")}
     short-label)
    ;; enter the question
    (taxi/clear {:xpath (label-text-input-xpath xpath "Question")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Question")}
     question)
    ;; required setting
    (set-radio-button (label-radio-input-xpath xpath "Must be answered?") required)
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
    ;; set the type
    (change-label-type xpath value-type)
    ;; Enter the display name
    (taxi/clear {:xpath (label-text-input-xpath xpath "Display Label")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Display Label")}
     short-label)
    ;; required setting
    (set-radio-button (label-radio-input-xpath xpath "Must be answered?") required)
    ;; allow multiple values?
    (set-radio-button (label-radio-input-xpath xpath "Allow Multiple Values?") multi?)
    ;; enter the question
    (taxi/clear {:xpath (label-text-input-xpath xpath "Question")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Question")}
     question)
    ;; enter the max length
    (taxi/clear {:xpath (label-text-input-xpath xpath "Max Length")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Max Length")}
     (str max-length))
    ;; Examples
    (taxi/clear {:xpath (label-text-input-xpath xpath "Examples (comma separated)")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath  "Examples (comma separated)")}
     (string/join "," examples))))

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
    ;; set the type
    (change-label-type xpath value-type)
    ;; Enter the display name
    (taxi/clear {:xpath (label-text-input-xpath xpath "Display Label")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Display Label")}
     short-label)
    ;; required setting
    (set-radio-button (label-radio-input-xpath xpath "Must be answered?") required)
    ;; enter the question
    (taxi/clear {:xpath (label-text-input-xpath xpath "Question")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Question")}
     question)
    ;; enter the categories
    (taxi/clear {:xpath (label-text-input-xpath xpath "Categories (comma separated options)")})
    (taxi/input-text
     {:xpath (label-text-input-xpath xpath "Categories (comma separated options)")}
     (string/join "," all-values))
    ;;  inclusion values
    (taxi/wait-until #(= (taxi/value {:xpath (label-text-input-xpath xpath "Categories (comma separated options)")})
                         (string/join "," all-values)))
    ;; set the inclusion values
    (doall (mapv #(let [inclusion-checkbox {:xpath (value-for-inclusion-checkbox xpath %)}
                        included? (contains? (set inclusion-values)
                                             %)]
                    ;; each time a selection is made, the checkboxes
                    ;; are re-rendered. Need to make sure they are all there here
                    (taxi/wait-until (fn [] (= (count all-values)
                                               (count (taxi/find-elements
                                                       {:xpath (str xpath "/descendant::"
                                                                    (label-name-xpath "for Inclusion")
                                                                    "/parent::div/" "/input[@type='checkbox']")})))))
                    (when (not= (taxi/selected? inclusion-checkbox)
                                included?)
                      (taxi/click inclusion-checkbox)))
                 all-values))))

(defn set-label-values
  "Given a label map, set the values accordingly in the browser"
  [xpath label-map]
  (let [{:keys [value-type]
         :or {value-type "boolean"}} label-map]
    (browser/wait-until-displayed {:xpath xpath})
    (condp = value-type
      "boolean"     (set-boolean-label-values xpath label-map)
      "string"      (set-string-label-values xpath label-map)
      "categorical" (set-categorical-label-values xpath label-map)
      )))

(deftest create-project-and-review-article
  (let [{:keys [email password]} browser/test-login
        project-name "Foo Bar"
        search-term-first "foo bar"]
    ;; register the user
    (browser/delete-test-user)
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
      (let [include-label-definition {:short-label "Include"}
            include-label-value true
            boolean-label-definition {:value-type "boolean"
                                      :question "Is this true or false?"
                                      :short-label "Boolean Label"
                                      :definition {:inclusion-values [true]}
                                      :required true}
            boolean-label-value true
            string-label-definition {:value-type "string"
                                     :question "What value is present for Foo?"
                                     :short-label "String Label"
                                     :definition
                                     {:max-length 160
                                      :examples ["foo" "bar" "baz" "qux"]
                                      :multi? true}
                                     :required true}
            string-label-value "Baz"
            categorical-label-definition {:question "Does this label fit within the categories?"
                                          :value-type "categorical"
                                          :required true
                                          :short-label "Categorical Label"
                                          :definition
                                          {:all-values ["Foo" "Bar" "Baz" "Qux"]
                                           :inclusion-values ["Foo" "Bar"]
                                           :multi? false}}
            categorical-label-value "Qux"]
        (taxi/click label-definitions-tab)
        (browser/wait-until-displayed add-boolean-label-button)
        ;; create a new boolean label
        (taxi/click add-boolean-label-button)
        (taxi/click save-labels-button)
        ;; there should be some errors
        (taxi/wait-until #(= (set (get-all-error-messages))
                             (set ["Label must have a name"
                                   "Question can not be blank"])))
        (is (= (set (get-all-error-messages))
               (set ["Label must have a name"
                     "Question can not be blank"])))
        ;; change the type of the label to string, check error messages
        (change-label-type label-item-div-with-errors "string")
        (taxi/click save-labels-button)
        (taxi/wait-until #(= (set (get-all-error-messages))
                             (set ["Label must have a name"
                                   "Question can not be blank"
                                   "Max Length must be provided"])))
        (is (= (set (get-all-error-messages))
               (set ["Label must have a name"
                     "Question can not be blank"
                     "Max Length must be provided"])))
        ;; change the type of the label to categorical, check error message
        (change-label-type label-item-div-with-errors "categorical")
        (taxi/click save-labels-button)
        (taxi/wait-until #(= (set (get-all-error-messages))
                             (set ["Label must have a name"
                                   "Question can not be blank"
                                   "A category must have defined options"])))
        (is (= (set (get-all-error-messages))
               (set ["Label must have a name"
                     "Question can not be blank"
                     "A category must have defined options"])))
        ;; delete this label
        (delete-label label-item-div-with-errors)
        (taxi/wait-until #(= 1
                             (count (taxi/find-elements {:xpath "//div[contains(@class,'label-item')]"}))))
        ;; create a boolean label
        (taxi/click add-boolean-label-button)
        (set-label-values "//div[contains(@id,'new-label-')]" boolean-label-definition)
        (taxi/click save-labels-button)
        ;; there is a new boolean label
        (browser/wait-until-displayed {:xpath (str "//span[text()='" (:short-label boolean-label-definition) "']")})
        (is (taxi/exists? {:xpath (str "//span[text()='" (:short-label boolean-label-definition) "']")}))
        ;; create a string label
        (taxi/click add-string-label-button)
        (set-label-values "//div[contains(@id,'new-label-')]" string-label-definition)
        (taxi/click save-labels-button)
        ;; there is a new string label
        (browser/wait-until-displayed {:xpath (str "//span[text()='" (:short-label string-label-definition) "']")})
        (is (taxi/exists? {:xpath (str "//span[text()='" (:short-label string-label-definition) "']")}))
        ;; create a categorical label
        (taxi/click add-categorical-label-button)
        (set-label-values "//div[contains(@id,'new-label-')]" categorical-label-definition)
        (taxi/click save-labels-button)
        ;; there is a new categorical label
        (browser/wait-until-displayed {:xpath (str "//span[text()='" (:short-label categorical-label-definition) "']")})
        (is (taxi/exists? {:xpath (str "//span[text()='" (:short-label categorical-label-definition) "']")}))
;;;; review an article
        (browser/wait-until-loading-completes)
        (browser/go-route "/")
        (Thread/sleep 500)
        (browser/go-route "/")
        (browser/wait-until-displayed review-articles-button)
        (taxi/click review-articles-button)
        (browser/wait-until-displayed {:xpath (label-div-with-name (:short-label include-label-definition))})
        ;; We shouldn't have any labels for this project
        (is (empty? (labels/query-public-article-labels project-id)))
        ;; Check the booleans
        (select-boolean-with-label-name (:short-label include-label-definition) include-label-value)
        (select-boolean-with-label-name (:short-label boolean-label-definition) boolean-label-value)
        ;; Input string
        (input-string-with-label-name (:short-label string-label-definition) string-label-value)
        ;; make a selection
        (select-with-text-label-name (:short-label categorical-label-definition) categorical-label-value)
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
                 (short-label-answer project-id article-id user-id (:short-label include-label-definition))))
          (is (= boolean-label-value
                 (short-label-answer project-id article-id user-id (:short-label boolean-label-definition))))
          (is (= string-label-value
                 (-> (short-label-answer project-id article-id user-id (:short-label string-label-definition))
                     first)))
          (is (= categorical-label-value
                 (-> (short-label-answer project-id article-id user-id (:short-label categorical-label-definition))
                     first)))
;;;; Let's check the actual UI for this
          (taxi/click articles-button)
          (browser/wait-until-displayed {:xpath (article-title-div article-title)})
          (taxi/click {:xpath (article-title-div article-title)})
          (browser/wait-until-displayed {:xpath "//div[contains(@class,'button') and contains(text(),'Change Labels')]"})
          ;; check overall include
          ;; note: booleans value name have ? appended to them
          (is (= include-label-value
                 (-> (label-button-value (str (:short-label include-label-definition) "?"))
                     read-string
                     boolean)))
          ;; check a boolean value
          (is (= boolean-label-value
                 (-> (label-button-value (str (:short-label boolean-label-definition) "?"))
                     read-string
                     boolean)))
          ;; check a string value
          (is (= string-label-value
                 (label-button-value (:short-label string-label-definition))))
          ;; check a categorical value
          (is (= categorical-label-value
                 (label-button-value (:short-label categorical-label-definition)))))
        ;; cleanup
        (navigate/log-out)
        ;; delete the project in the database
        (project/delete-project project-id)
        ))))
