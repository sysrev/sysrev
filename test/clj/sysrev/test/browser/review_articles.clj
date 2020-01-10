(ns sysrev.test.browser.review-articles
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.db.core :as db]
            [sysrev.label.core :as labels]
            [sysrev.project.core :as project]
            [sysrev.user.core :as user :refer [user-self-info]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.define-labels :as define]))

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
;; (user-self-info (user-by-email ... :user-id))

;; delete the project
;; (let [project-ids (->> (user-by-email ...) :user-id user-self-info :projects (mapv :project-id) (filterv #(not= % 100)))] (mapv #(project/delete-project %) project-ids))

;; useful definitions after basic values have been set by tests
;; (def email "browser+test@insilica.co")
;; (def password b/test-password)
;; (def user-id (:user-id (user-by-email email)))
;; (def project-id (get-user-project-id user-id))
;; (def article-title (-> (labels/query-public-article-labels project-id) vals first :title))
;; (def article-id (-> (labels/query-public-article-labels project-id) keys first))

;; for clj-kondo
(declare set-boolean-value add-string-value add-categorical-value
         categorical-active-values remove-categorical-value)

(defn label-div-with-name [short-label]
  (xpath "//span[contains(@class,'name')]"
         (format "/span[contains(@class,'inner') and text()='%s']" short-label)
         "/ancestor::div[contains(@class,'label-edit')"
         " and contains(@class,'column')]"))

(defn-spec set-boolean-value any?
  "Sets boolean label `short-label` to `value` in review interface."
  [short-label string?, value (s/nilable boolean?)]
  (b/click (xpath (label-div-with-name short-label)
                  (format "/descendant::div[text()='%s']"
                          (case value true "Yes" false "No" nil "?"))))
  (Thread/sleep 30))

(defn string-plus-button-xpath [short-label]
  (xpath (label-div-with-name short-label)
         "/descendant::i[contains(@class,'plus')]"
         "/ancestor::div[contains(@class,'button')"
         " and contains(@class,'input-row')"
         " and !contains(@class,'disabled')]"))

(defn string-plus-buttons [short-label]
  (seq (->> (string-plus-button-xpath short-label)
            (taxi/find-elements)
            (filterv taxi/displayed?))))

(defn string-input-xpath [short-label & [value]]
  (xpath (label-div-with-name short-label)
         (if value
           (format "/descendant::input[@type='text' and @value='%s']" value)
           "/descendant::input[@type='text']")))

(defn-spec add-string-value any?
  "Adds `value` to string label `short-label` in review interface."
  [short-label string?, value string?]
  (let [q (string-input-xpath short-label "")]
    (when-not (taxi/exists? q)
      (if (taxi/exists? (string-plus-button-xpath short-label))
        (b/click (last (string-plus-buttons short-label)))
        (taxi/clear (string-input-xpath short-label))))
    (let [node (taxi/element q)]
      (b/set-input-text node value)
      (taxi/send-keys node org.openqa.selenium.Keys/ENTER)
      (Thread/sleep 30))))

(defn-spec string-active-values (s/coll-of string? :kind vector?)
  "Returns vector of current values in component for `short-label`."
  [short-label string?]
  (->> (taxi/find-elements (string-input-xpath short-label))
       (filterv taxi/displayed?)
       (mapv taxi/value)
       (filterv not-empty)))

(defn-spec remove-string-value any?
  "Removes `value` from string label `short-label` in review interface."
  [short-label string?, value string?]
  (let [node (taxi/element (string-input-xpath short-label value))]
    (taxi/focus node)
    (taxi/clear node)
    (taxi/send-keys node org.openqa.selenium.Keys/BACK_SPACE)
    (when-let [plus-icons (string-plus-buttons short-label)]
      (taxi/click (last plus-icons)))))

(defn-spec add-categorical-value any?
  "Adds `value` to categorical label `short-label` in review interface."
  [short-label string?, value string?]
  (let [dropdown-div (xpath (label-div-with-name short-label)
                            "/descendant::div[contains(@class,'dropdown')]")
        entry-div (xpath (label-div-with-name short-label)
                         "/descendant::div[contains(text(),'" value "')]")]
    (b/click dropdown-div :displayed? true :delay 75)
    (b/click entry-div :displayed? true :delay 75)))

(defn-spec categorical-active-values (s/coll-of string? :kind vector?)
  "Returns vector of selected values in dropdown component for `short-label`."
  [short-label string?]
  (->> (taxi/find-elements (xpath (label-div-with-name short-label)
                                  "/descendant::a[contains(@class,'label')]"))
       (mapv taxi/text)))

(defn-spec remove-categorical-value any?
  "Removes `value` from categorical label `short-label` in review interface."
  [short-label string?, value string?]
  (b/click (xpath (label-div-with-name short-label)
                  (format "/descendant::a[contains(@class,'label') and text()='%s']"
                          value))))

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
    (get-in (labels/article-user-labels-map article-id)
            [user-id label-uuid :answer])))

(defn get-user-project-id
  "Return the first project-id of user-id"
  [user-id]
  (-> user-id user-self-info :projects first :project-id))

(defn set-label-answer
  "Set answer value for a single label on current article."
  [{:keys [short-label value value-type]}]
  (as-> (case value-type
          "boolean" set-boolean-value
          "string" add-string-value
          "categorical" add-categorical-value) f
    (f short-label value)))

(defn set-article-answers
  "Set and save answers on current article for a sequence of labels."
  [label-settings]
  (let [remote? (test/remote-test?)]
    (log/info "setting article labels")
    (nav/go-project-route "/review" :silent true :wait-ms 50)
    (b/wait-until-loading-completes :pre-wait (if remote? 100 30) :loop 2)
    (b/click x/review-labels-tab :delay 25 :displayed? true)
    (doseq [x label-settings] (set-label-answer x))
    (when remote? (Thread/sleep 100))
    (b/click ".button.save-labels" :delay 30 :displayed? true)
    (b/wait-until-loading-completes :pre-wait (if remote? 150 30) :loop 2)
    (some-> (b/current-project-id)
            (db/clear-project-cache))))

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
  (test/db-connected?) test-user
  [{:keys [user-id email]} test-user
   project-id (atom nil)
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
   new-label "//div[contains(@id,'new-label-')]"]
  (do (nav/log-in email)
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
        (is (= string-label-value
               (first (short-label-answer @project-id article-id user-id
                                          (:short-label string-label-definition)))))
        (is (= categorical-label-value
               (first (short-label-answer @project-id article-id user-id
                                          (:short-label categorical-label-definition)))))
        (log/info "checking label values from editor")
;;;; Let's check the actual UI for this
        (nav/go-project-route "/articles" :wait-ms 50)
        (b/click "a.article-title")
        (b/wait-until-displayed ".ui.button.change-labels")
        ;; check overall include
        ;; note: booleans value name have ? appended to them
        (is (= include-label-value
               (-> (str (:short-label include-label-definition) "?")
                   label-button-value read-string boolean)))
        ;; check a string value
        (is (= string-label-value
               (label-button-value (:short-label string-label-definition))))
        ;; check a categorical value
        (is (= categorical-label-value
               (label-button-value (:short-label categorical-label-definition))))))
  :cleanup (some-> @project-id (project/delete-project)))

(deftest-browser review-label-components
  (test/db-connected?) test-user
  [{:keys [user-id email]} test-user
   project-id (atom nil)
   project-name "Sysrev Browser Test (review-label-components)"
   labels [{:value-type "boolean"
            :short-label "Include"
            :required true}
           {:value-type "categorical"
            :question "Which numbers?"
            :short-label "Numbers"
            :required true
            :consensus false
            :definition {:all-values ["one" "two" "three" "four & five" "five & six"]
                         :inclusion-values ["two" "three"]}}
           {:value-type "string"
            :short-label "Text"
            :question "Enter some text"
            :required false
            :definition {:examples ["1 mg" "2.4 kg"]
                         :max-length 100
                         :multi? true}}]
   [label0 label1 label2] labels
   [name0 name1 name2] (map :short-label labels)]
  (do (nav/log-in email)
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (assert (integer? @project-id))
      (pm/import-pubmed-search-via-db "foo bar")

;;; create new labels
      (log/info "creating label definitions")
      (nav/go-project-route "/labels/edit")
      (define/define-label label1)
      (is (b/exists? (x/match-text "span" name1)))
      (define/define-label label2)
      (is (b/exists? (x/match-text "span" name2)))
;;;; review an article
      (nav/go-project-route "")
      (b/click (x/project-menu-item :review) :delay 50)
      (b/click x/review-labels-tab)
      (b/wait-until-loading-completes :pre-wait 50 :loop 2)
      (log/info "testing categorical component")
      (add-categorical-value name1 "one")
      (add-categorical-value name1 "three")
      (add-categorical-value name1 "four & five")
      (b/is-soon (= (categorical-active-values name1) ["one" "three" "four & five"]))
      (remove-categorical-value name1 "three")
      (b/is-soon (= (categorical-active-values name1) ["one" "four & five"]))
      (add-categorical-value name1 "five & six")
      (b/is-soon (= (categorical-active-values name1) ["one" "four & five" "five & six"]))
      (remove-categorical-value name1 "four & five")
      (b/is-soon (= (categorical-active-values name1) ["one" "five & six"]))
      (remove-categorical-value name1 "five & six")
      (b/is-soon (= (categorical-active-values name1) ["one"]))
      (add-categorical-value name1 "three")
      (b/is-soon (= (categorical-active-values name1) ["one" "three"]))
      (b/exists? ".ui.button.save-labels.disabled")
      (log/info "testing boolean component")
      (set-boolean-value name0 false)
      (b/exists? (b/not-disabled ".ui.button.save-labels"))
      (set-boolean-value name0 nil)
      (b/exists? ".ui.button.save-labels.disabled")
      (set-boolean-value name0 true)
      (b/exists? (b/not-disabled ".ui.button.save-labels"))
      (log/info "testing string component")
      (add-string-value name2 "1")
      (b/is-soon (= (string-active-values name2) ["1"]))
      (add-string-value name2 "2")
      (b/is-soon (= (string-active-values name2) ["1" "2"]))
      (remove-string-value name2 "1")
      (b/is-soon (= (string-active-values name2) ["2"]))
      (add-string-value name2 "33")
      (b/is-soon (= (string-active-values name2) ["2" "33"]))
      (remove-string-value name2 "33")
      (b/is-soon (= (string-active-values name2) ["2"]))
      (add-string-value name2 "45")
      (b/is-soon (= (string-active-values name2) ["2" "45"])))
  :cleanup (some-> @project-id (project/delete-project)))

(defn randomly-review-n-articles
  "Randomly sets labels for n articles using a vector of label-definitions"
  [n label-definitions]
  (nav/go-project-route "/review")
  (b/wait-until-displayed "#project_review")
  (dotimes [_ n]
    (when-not (b/displayed-now? ".no-review-articles")
      (randomly-set-article-labels label-definitions)
      (b/wait-until #(or (b/displayed-now? ".ui.button.save-labels.disabled")
                         (b/displayed-now? ".no-review-articles"))))))

(defn randomly-review-all-articles
  "Randomly sets labels for articles until all have been reviewed"
  [label-definitions]
  (b/click (x/project-menu-item :review))
  (b/wait-until-displayed "#project_review")
  (while (not (b/displayed-now? ".no-review-articles"))
    (randomly-set-article-labels label-definitions)
    (b/wait-until #(or (b/displayed-now? ".ui.button.save-labels.disabled")
                       (b/displayed-now? ".no-review-articles")))))

;; (randomly-review-all-articles [(merge include-label-definition {:all-values [true false]})
;; (randomly-review-n-articles 15 [(merge include-label-definition {:all-values [true false]})])
