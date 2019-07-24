(ns sysrev.test.browser.review-articles
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [sysrev.db.core :as db]
            [sysrev.label.core :as labels]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.stripe :as stripe]
            [sysrev.test.core :as test :refer [wait-until]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.define-labels :as define]
            [clojure.tools.logging :as log]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

;; Note: There are different classes / ids / etc. for elements in mobile versus desktop
;;       If you are having an issue with not finding elements that should be there,
;;       try resizing the window to desktop to see if there is a discrepancy and then fix it
;;       in the element xpath names.
;;       You should have consistent test behavior whether you are in mobile or desktop!!!
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

(defn label-div-with-name [name]
  (xpath "//span[contains(@class,'name')]"
         "/span[contains(@class,'inner') and text()='" name "']"
         "/ancestor::div[contains(@class,'label-edit')"
         " and contains(@class,'column')]"))

(defn select-boolean-with-label-name
  "Use boolean select? to set the value of label with name.
  select? being true corresponds to 'Yes', false corresponds to 'No'"
  [name select?]
  (b/click (xpath (label-div-with-name name)
                  "/descendant::div[contains(text(),'"
                  (if select? "Yes" "No") "')]"))
  (Thread/sleep 50))

(defn input-string-with-label-name
  "Input string into label with name"
  [name string]
  (b/set-input-text (xpath (label-div-with-name name)
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
    (Thread/sleep 300)
    (b/click entry-div :displayed? true)
    (Thread/sleep 100)))

(defn article-title-div [title]
  (xpath "//div[contains(@class,'article-title') and contains(text(),'" title "')]"))

(defn label-button-value [label]
  (taxi/text (xpath "//div[contains(@class,'button') and contains(text(),'" label "')]"
                    "/parent::div[contains(@class,'label-answer-tag')]"
                    "/div[contains(@class,'label')]")))

;;;; end element definitions

;;;; label definitions
(def include-label-definition {:value-type "boolean"
                               :short-label "Include"
                               :required true})

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
  (let [label-uuid (->> (vals (project/project-labels project-id))
                        (filter #(= short-label (:short-label %)))
                        first :label-id)]
    (get-in (labels/article-user-labels-map project-id article-id)
            [user-id label-uuid :answer])))

(defn get-user-project-id
  "Return the first project-id of user-id"
  [user-id]
  (-> user-id users/user-self-info :projects first :project-id))

(defn set-label-answer
  "Set answer value for a single label on current article."
  [{:keys [short-label value value-type]}]
  (as-> (case value-type
          "boolean" select-boolean-with-label-name
          "string" input-string-with-label-name
          "categorical" select-with-text-label-name) f
    (f short-label value)))

(defn set-article-answers
  "Set and save answers on current article for a sequence of labels."
  [label-settings]
  (log/info "setting article labels")
  (nav/go-project-route "/review" :silent true :wait-ms 50)
  (when (test/remote-test?) (Thread/sleep 500))
  (b/click x/review-labels-tab :delay 50 :displayed? true)
  (doseq [x label-settings] (set-label-answer x))
  (when (test/remote-test?) (Thread/sleep 500))
  (b/click ".button.save-labels" :delay 50 :displayed? true)
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-loading-completes)
  (db/clear-query-cache))

(defn randomly-set-article-labels
  "Given a vector of label-settings maps, set the labels for an article
  in the browser, randomly choosing from all possible values (or all
  example values)."
  [label-settings]
  (set-article-answers
   (mapv (fn [label]
           (let [all-values (if (= (:value-type label) "boolean")
                              [true false]
                              (or (get-in label [:all-values])
                                  (get-in label [:definition :all-values])
                                  (get-in label [:definition :examples])))]
             (merge label {:value (nth all-values (rand-int (count all-values)))})))
         label-settings)))

(deftest-browser create-project-and-review-article
  (test/db-connected?)
  [project-id (atom nil)
   project-name "Sysrev Browser Test (create-project-and-review-article)"
   no-display "Display name must be provided"
   no-question "Question text must be provided"
   no-max-length "Max length must be provided"
   no-options "Category options must be provided"
   include-label-value true
   boolean-label-value true
   string-label-value "Baz"
   categorical-label-value "Qux"
   have-errors-now? #(= (set %) (set (define/get-all-error-messages)))
   have-errors? (fn [errors] (b/try-wait b/wait-until #(have-errors-now? errors) 2500))
   new-label "//div[contains(@id,'new-label-')]"
   {:keys [user-id]} (users/get-user-by-email (:email b/test-login))]
  (do (nav/log-in)
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (assert (integer? @project-id))
      (pm/import-pubmed-search-via-db "foo bar")

;;; create new labels
      (log/info "creating label definitions")
      (nav/go-project-route "/labels/edit")
      ;; create a new boolean label
      (b/click define/add-boolean-label-button)
      (define/save-label)
      ;; there should be some errors
      (is (have-errors? [no-display no-question]))
      (define/discard-label)
      ;; change the type of the label to string, check error messages
      (b/click define/add-string-label-button)
      (define/save-label)
      (is (have-errors? [no-display no-question]))
      (define/discard-label)
      ;; change the type of the label to categorical, check error message
      (b/click define/add-categorical-label-button)
      (define/save-label)
      (is (have-errors? [no-display no-question no-options]))
      (define/discard-label)
      ;; create a boolean label
      #_ (do (b/click add-boolean-label-button)
             (set-label-values new-label boolean-label-definition)
             (save-label)
             ;; there is a new boolean label
             (is (b/exists? (x/match-text
                             "span" (:short-label boolean-label-definition)))))

      ;; create a string label
      (define/define-label string-label-definition)
      ;; there is a new string label
      (is (b/exists? (x/match-text
                      "span" (:short-label string-label-definition))))
      ;; create a categorical label
      (define/define-label categorical-label-definition)
      ;; there is a new categorical label
      (is (b/exists? (x/match-text "span" (:short-label categorical-label-definition))))
;;;; review an article
      (nav/go-project-route "")
      (b/click (x/project-menu-item :review) :delay 50)
      (b/click x/review-labels-tab)
      (b/wait-until-displayed
       (label-div-with-name (:short-label include-label-definition)))
      ;; We shouldn't have any labels for this project
      (is (empty? (labels/query-public-article-labels @project-id)))
      ;; set the labels
      (set-article-answers [(merge include-label-definition
                                   {:value include-label-value})
                            #_ (merge boolean-label-definition
                                      {:value boolean-label-value})
                            (merge string-label-definition
                                   {:value string-label-value})
                            (merge categorical-label-definition
                                   {:value categorical-label-value})])
      ;;verify we are on the next article
      (is (b/exists? ".ui.button.save-labels.disabled"))
;;;; check in the database for the labels
      ;; we have labels for just one article
      (is (= 1 (count (labels/query-public-article-labels @project-id))))
      (log/info "checking label values from db")
      (let [ ;; this is not yet generalized
            article-id (-> (labels/query-public-article-labels
                            @project-id) keys first)]
        ;; these are just checks in the database
        (is (= include-label-value
               (short-label-answer @project-id article-id user-id
                                   (:short-label include-label-definition))))
        #_ (is (= boolean-label-value
                  (short-label-answer @project-id article-id user-id
                                      (:short-label boolean-label-definition))))
        (is (= string-label-value
               (first (short-label-answer @project-id article-id user-id
                                          (:short-label string-label-definition)))))
        (is (= categorical-label-value
               (first (short-label-answer @project-id article-id user-id
                                          (:short-label categorical-label-definition)))))
        (log/info "checking label values from editor")
;;;; Let's check the actual UI for this
        (nav/go-project-route "/articles")
        (b/wait-until-loading-completes :pre-wait 50)
        (b/click "a.article-title")
        (b/wait-until-displayed ".ui.button.change-labels")
        ;; check overall include
        ;; note: booleans value name have ? appended to them
        (is (= include-label-value
               (-> (str (:short-label include-label-definition) "?")
                   label-button-value read-string boolean)))
        ;; check a boolean value
        #_ (is (= boolean-label-value
                  (-> (str (:short-label boolean-label-definition) "?")
                      label-button-value read-string boolean)))
        ;; check a string value
        (is (= string-label-value
               (label-button-value (:short-label string-label-definition))))
        ;; check a categorical value
        (is (= categorical-label-value
               (label-button-value (:short-label categorical-label-definition))))))

  :cleanup (some-> @project-id (project/delete-project)))

(defn randomly-review-all-articles
  "Randomly sets labels for articles until all have been reviewed"
  [label-definitions]
  (b/click (x/project-menu-item :review))
  (b/wait-until-displayed "#project_review")
  (while (not (b/displayed-now? ".no-review-articles"))
    (randomly-set-article-labels label-definitions)
    (b/wait-until #(or (b/displayed-now? ".ui.button.save-labels.disabled")
                       (b/displayed-now? ".no-review-articles")))))

(defn randomly-review-n-articles
  "Randomly sets labels for n articles using a vector of label-definitions"
  [n label-definitions]
  (nav/go-project-route "/review")
  (b/wait-until-displayed "#project_review")
  (dotimes [i n]
    (when-not (b/displayed-now? ".no-review-articles")
      (randomly-set-article-labels label-definitions)
      (b/wait-until #(or (b/displayed-now? ".ui.button.save-labels.disabled")
                         (b/displayed-now? ".no-review-articles"))))))

;; (randomly-review-all-articles [(merge include-label-definition {:all-values [true false]})
;; (randomly-review-n-articles 15 [(merge include-label-definition {:all-values [true false]})])
