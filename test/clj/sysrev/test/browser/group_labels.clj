(ns sysrev.test.browser.group-labels
  (:require [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [honeysql.helpers :as hsql :refer [select from where]]
            [medley.core :as medley]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.label.core :as labels]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.define-labels :as dlabels]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pubmed]
            [sysrev.test.browser.review-articles :as ra]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn group-label-div-with-name [short-label]
  (xpath "//div[contains(@class,'group-label-instance')]//div[contains(@class,'title') and contains(text(),'" short-label "')]"))

;; https://stackoverflow.com/questions/14745478/how-to-select-table-column-by-column-header-name-with-xpath
;; note: this is fairly hacky, won't work with multiple values
(defn group-label-button-value
  [short-label ith]
  (taxi/text (xpath "(//table/tbody/tr/td[count(//table/thead/tr/th[.='" short-label "']/preceding-sibling::th)+1])[" ith "]")))

(defn group-label-div
  "Given a short-label name for group, return its root xpath"
  [short-label]
  (x/xpath "//span[contains(text(),'" short-label "')]/ancestor::div[contains(@class,'define-label-item')][1]"))

(defn sub-group-label-div
  "Given a root short-label for a group and its sublabel short-label, return its root xpath"
  [root-short-label sub-short-label]
  (x/xpath (group-label-div root-short-label) "//span[contains(text(),'" sub-short-label "')]/ancestor::div[contains(@id,'sub-label-')]"))

(defn group-sub-short-labels
  "Given a group label short-label, return the sub short-labels"
  [root-short-label]
  (->> (x/xpath (group-label-div root-short-label) "//span[contains(@class,'short-label')]")
       taxi/find-elements
       (map taxi/text)
       (into [])))

(defn group-sub-short-labels-review
  "In the review interface, return the sub short-labels"
  [root-short-label]
  (->> (x/xpath "(//div[contains(@class,'title') and contains(text(),'" root-short-label "')]//ancestor::div[contains(@id,'group-label-input-')])[1]"
                "//span[contains(@class,'short-label')]")
       taxi/find-elements
       (map taxi/text)
       (into [])))

(defn edit-group-label-button
  [root-short-label]
  (->> (x/xpath "//span[contains(text(),'" root-short-label "')]"
                "/ancestor::div[contains(@id,'group-label-')]"
                "//div[contains(@class,'edit-label-button')]")))

(defn group-label-instance
  [instance-name]
  (x/xpath (str "//div[contains(text(),'" instance-name "')]/ancestor::div[contains(@class,'group-label-instance')]")))

(defn group-label-instance-sub-label
  [instance-name sub-short-label]
  (x/xpath (group-label-instance instance-name) "//span[contains(@class,'short-label') and contains(text(),'" sub-short-label "')]//ancestor::div[contains(@id,'group-sub-label')]"))

(defn group-label-edit-form
  [root-short-label]
  (x/xpath "(//input[@value='" root-short-label "']"
           "/ancestor::div[contains(@id,'group-label-')])[1]"))

(defn group-label-edit-form-sub-labels
  "Given a group label short-label, return the sub short-label values of the input forms in an edit group label form"
  [root-short-label]
  (->> (x/xpath (group-label-edit-form root-short-label)
                "//div[contains(@class,'sub-labels-edit-form')]"
                "//div[contains(@class,'field-short-label')]/input")
       taxi/find-elements
       (map taxi/value)
       (into [])))

(defn toggle-enable-disable-sub-label-button
  "If enabled is :enable, enable the button, if :disable, disable it"
  [root-short-label sub-short-label enabled]
  (b/click (edit-group-label-button root-short-label))
  (b/click (x/xpath (group-label-edit-form root-short-label)
                    "//input[@value='" sub-short-label "']"
                    "/ancestor::form"
                    "//button[contains(text(),'" (condp = enabled
                                                   :enable "Enable"
                                                   :disable "Disable")
                    "')]")))

(defn move-short-label
  "Move the short-label in the direction, which is :up or :down"
  [root-short-label sub-short-label direction]
  (b/click (x/xpath
            (sub-group-label-div root-short-label sub-short-label)
            "//div[contains(text(),'Move " (condp = direction
                                             :up "Up"
                                             :down "Down") "')]")))

(defn group-label-disabled?
  [root-short-label]
  (and (b/exists? (x/xpath "//h4[contains(text(),'Disabled Labels')]"))
       (b/exists? (x/xpath (group-label-div root-short-label) "//div[text()='There are disabled labels, edit label to view them']"))))

(defn add-group-label-review
  "Add an additional group label when reviewing"
  [root-short-label]
  (x/xpath "//div[contains(@class,'button') and contains(text(),'" root-short-label "')]"))

(defn group-label-value
  "In a group label answer, get the short-label values"
  [short-label group-label-definition]
  (->>
   (get-in group-label-definition [:definition :labels])
   (medley/find-first #(= (:short-label %) short-label)) :value))

(deftest-browser group-labels-happy-path
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-labels-happy-path-test)"
   project-id (atom nil)
   {:keys [user-id email]} test-user
   include-label-value true
   group-label-definition {:value-type "group"
                           :short-label "Group Label"
                           :definition
                           {:labels [{:value-type "boolean"
                                      :short-label "Boolean Label"
                                      :question "Is this true or false?"
                                      :definition {:inclusion-values [true]}
                                      :required true
                                      :value true}
                                     {:value-type "string"
                                      :short-label "String Label"
                                      :question "What value is present for Foo?"
                                      :definition
                                      {:max-length 160
                                       :examples ["foo" "bar" "baz" "qux"]
                                       :multi? true}
                                      :required true
                                      :value "Baz"}
                                     {:value-type "categorical"
                                      :short-label "Categorical Label"
                                      :question "Does this label fit within the categories?"
                                      :definition
                                      {:all-values ["Foo" "Bar" "Baz" "Qux"]
                                       :inclusion-values ["Foo" "Bar"]
                                       :multi? false}
                                      :required true
                                      :value "Qux"}]}}]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (reset! project-id (b/current-project-id))
    ;; PubMed search input
    (b/click (xpath "//a[contains(text(),'PubMed Search')]"))
    (pubmed/search-pubmed "foo bar")
    (b/click x/import-button-xpath)
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (is (b/exists? (xpath "//span[@class='unique-count' and contains(text(),'6')]")))
    ;; create new labels
    (log/info "Creating Group Label Definitions")
    (nav/go-project-route "/labels/edit")
    (dlabels/define-group-label group-label-definition)
    ;; make sure the labels are in the correct order
    (is (= (->> group-label-definition :definition :labels (mapv :short-label))
           (group-sub-short-labels "Group Label")))
    ;; review an article
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review) :delay 50)
    (b/click x/review-labels-tab)
    (b/wait-until-displayed
     (group-label-div-with-name (:short-label group-label-definition)))
    ;; we shouldn't have any labels for this project
    (is (empty? (labels/query-public-article-labels @project-id)))
    ;; set the labels
    (ra/set-article-answers (conj (-> group-label-definition :definition :labels)
                                  (merge ra/include-label-definition
                                         {:value include-label-value}))
                            :save? false)
    (is (b/exists? (xpath "//th[contains(text(),'"
                          (:short-label group-label-definition) "')]")))
    (b/click ".button.save-labels" :delay 30 :displayed? true)
    ;;verify we are on the next article
    (is (b/exists? ".ui.button.save-labels.disabled"))
    ;; check in the database for the labels
    (is (= 1 (count (labels/query-public-article-labels @project-id))))
    (log/info "checking label values from db")
    (let [ ;; this is not yet generalized
          article-id (-> (labels/query-public-article-labels
                          @project-id) keys first)
          uuid->short-label-map (-> (select :label-id :short-label)
                                    (from :label)
                                    (where [:= :project-id @project-id])
                                    do-query
                                    (->> (map #(hash-map (:short-label %) (:label-id %)))
                                         (apply merge)))
          group-label-settings (-> (ra/short-label-answer @project-id article-id user-id "Group Label") :labels (get "0"))
          group-label-setting (fn [short-label]
                                (let [answer (->> short-label
                                                  (get uuid->short-label-map)
                                                  (get group-label-settings))]
                                  (if (vector? answer)
                                    (first answer)
                                    answer)))
          test-short-label-answer (fn [short-label]
                                    (is (= (group-label-value short-label group-label-definition)
                                           (group-label-setting short-label))))]
      ;; these are just checks in the database
      (is (= true
             (ra/short-label-answer @project-id article-id user-id
                                    "Include")))
      (test-short-label-answer "Boolean Label")
      (test-short-label-answer "Categorical Label")
      (test-short-label-answer "String Label")
      (log/info "checking label values from editor")
;;;; Let's check the actual UI for this
      (nav/go-project-route "/articles" :wait-ms 50)
      (b/click "a.article-title")
      (b/wait-until-displayed ".ui.button.change-labels")
      ;; check overall include
      ;;note: booleans value name have ? appended to them
      (is (= include-label-value
             (-> (str (:short-label ra/include-label-definition) "?")
                 ra/label-button-value read-string boolean)))
      ;; check a boolean value
      (is (= (str (group-label-value "Boolean Label" group-label-definition))
             (group-label-button-value "Boolean Label" "1")))
      ;; check a string value
      (is (= (group-label-value "String Label" group-label-definition)
             (group-label-button-value "String Label" "1")))
      ;; check a categorical value
      (is (= (group-label-value "Categorical Label" group-label-definition)
             (group-label-button-value "Categorical Label" "1")))))
  :cleanup (b/cleanup-test-user! :email (:email test-user)))

(deftest-browser group-labels-error-handling-test
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-labels-error-handling-test)"
   {:keys [user-id email]} test-user
   project-id (atom nil)
   no-display "Display name must be provided"
   sub-label-required "Group label must include at least one sub label"
   question-required "Question text must be provided"
   options-required "Category options must be provided"
   group-label-defintion-1 {:value-type "group"
                            :short-label "Group Label"
                            :definition {:labels [{:value-type "boolean"
                                                   :short-label "Boolean Label"
                                                   :question "Is this true or false?"
                                                   :definition {:inclusion-values [true]}
                                                   :required true
                                                   :value true}
                                                  ]}}
   group-label-definition-2 {:value-type "group"
                             :short-label "Group Label 2"
                             :definition
                             {:labels [{:value-type "boolean"
                                        :short-label "Boolean Label"
                                        :question "Is this true or false?"
                                        :definition {:inclusion-values [true]}
                                        :required true
                                        :value true}
                                       {:value-type "string"
                                        :short-label "String Label"
                                        :question "What value is present for Foo?"
                                        :definition
                                        {:max-length 160
                                         :examples ["foo" "bar" "baz" "qux"]
                                         :multi? true}
                                        :required true
                                        :value "Baz"}
                                       {:value-type "categorical"
                                        :short-label "Categorical Label"
                                        :question "Does this label fit within the categories?"
                                        :definition
                                        {:all-values ["Foo" "Bar" "Baz" "Qux"]
                                         :inclusion-values ["Foo" "Bar"]
                                         :multi? false}
                                        :required true
                                        :value "Qux"}]}}]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (reset! project-id (b/current-project-id))
    (log/info "Testing Group Label editor error handling")
    ;; add some article so we can label them
    (b/click (xpath "//a[contains(text(),'PubMed Search')]"))
    (pubmed/search-pubmed "foo bar")
    (b/click x/import-button-xpath)
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (is (b/exists? (xpath "//span[@class='unique-count' and contains(text(),'6')]")))
    ;; label editing
    (nav/go-project-route "/labels/edit")
    (b/click dlabels/add-group-label-button)
    ;; try to save it, should have some error messages
    (b/click dlabels/save-button)
    (is (ra/have-errors? [no-display sub-label-required]))
    ;; give the label a name, does the no-display message disappear?
    (b/set-input-text (x/xpath "//div[contains(@class,'field-short-label')]//input") "Group Label")
    (b/click dlabels/save-button)
    (is (ra/have-errors? [sub-label-required]))
    ;; add a simple boolean label, do all of the error messages go away?
    (b/click (x/xpath (dlabels/add-label-button "boolean") "/ancestor::div[contains(@class,'group')]/button"))
    (dlabels/set-label-definition (x/xpath "(//div[contains(@class,'define-group-label')]//form[contains(@class,'define-label')])[" 1 "]")
                                  (first (get-in group-label-defintion-1 [:definition :labels])))
    (b/click dlabels/save-button)
    ;; make sure that we can't move this label
    ;; this test is a little iffy in that it expects something NOT to exist
    (is (not (taxi/exists? (x/xpath (sub-group-label-div "Group Label" "Boolean Label") "//div[contains(text(),'Move')]")))) 
    (is (ra/have-errors? []))
    ;; Make sure that an invalid sub label can't be saved
    (b/click (edit-group-label-button "Group Label"))
    (b/click (x/xpath (dlabels/add-label-button "categorical") "/ancestor::div[contains(@class,'group')]/button"))
    (b/click dlabels/save-button)
    (is (ra/have-errors? [no-display question-required options-required]))
    ;; just discard this
    (b/click (x/xpath "//button[contains(text(),'Discard')]"))
    (b/click (x/xpath "(//button[contains(text(),'Cancel')])[2]"))
    ;; all errors are gone
    (is (ra/have-errors? []))
    ;; Let's make a more complicated label and test moving labels up/down and disabling/enabling
    ;; a group label
    (log/info "Testing moving group labels")
    (dlabels/define-group-label group-label-definition-2)
    (b/wait-until #(= ["Boolean Label" "String Label" "Categorical Label"]
                      (group-sub-short-labels "Group Label 2")))
    (is (= ["Boolean Label" "String Label" "Categorical Label"]
           (group-sub-short-labels "Group Label 2")))
    ;; check that the buttons have the correct "Move Down/Move Up" Buttons
    (is (b/exists? (x/xpath (sub-group-label-div "Group Label 2" "Boolean Label") "//div[contains(text(),'Move Down')]")))
    ;; Move Down doesn't exist
    (is (not (taxi/exists? (x/xpath (sub-group-label-div "Group Label 2" "Boolean Label") "//div[contains(text(),'Move Up')]")))) 
    (is (b/exists? (x/xpath (sub-group-label-div "Group Label 2" "String Label") "//div[contains(text(),'Move Up')]")))
    (is (b/exists? (x/xpath (sub-group-label-div "Group Label 2" "String Label") "//div[contains(text(),'Move Down')]")))
    (is (b/exists? (x/xpath (sub-group-label-div "Group Label 2" "Categorical Label") "//div[contains(text(),'Move Up')]")))
    (is (not (taxi/exists? (x/xpath (sub-group-label-div "Group Label 2" "Categorical Label") "//div[contains(text(),'Move Down')]"))))
    ;; move boolean down, is the order correct?
    (move-short-label "Group Label 2" "Boolean Label" :down)
    ;; are the orders correct?
    (b/wait-until #(= ["String Label" "Boolean Label" "Categorical Label"]
                      (group-sub-short-labels "Group Label 2")))
    (is (= ["String Label" "Boolean Label" "Categorical Label"]
           (group-sub-short-labels "Group Label 2")))
    ;; check to make sure the review editor has them in the correct order
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review) :delay 50)
    (b/wait-until #(= ["String Label" "Boolean Label" "Categorical Label"]
                      (group-sub-short-labels-review "Group Label 2")))
    (is (= ["String Label" "Boolean Label" "Categorical Label"]
           (group-sub-short-labels-review "Group Label 2")))
    ;; test label disabling String Label
    (log/info "Testing disabling / enabling labels")
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "String Label" :disable)
    (b/wait-until #(= ["Boolean Label" "Categorical Label"]
                      (group-sub-short-labels "Group Label 2")))
    (is (= ["Boolean Label" "Categorical Label"]
           (group-sub-short-labels "Group Label 2")))
    ;; is this disabled in the review view too?
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review) :delay 50)
    (b/wait-until #(= ["Boolean Label" "Categorical Label"]
                      (group-sub-short-labels-review "Group Label 2")))
    (is (= ["Boolean Label" "Categorical Label"]
           (group-sub-short-labels-review "Group Label 2")))
    ;; test disabling the Boolean Label
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "Categorical Label" :disable)
    (b/wait-until #(= ["Boolean Label"]
                      (group-sub-short-labels "Group Label 2")))
    (is (= ["Boolean Label"]
           (group-sub-short-labels "Group Label 2")))
    ;; is this disabled in the review view too?
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review) :delay 50)
    (b/wait-until #(= ["Boolean Label"]
                      (group-sub-short-labels-review "Group Label 2")))
    (is (= ["Boolean Label"]
           (group-sub-short-labels-review "Group Label 2")))
    ;; completely disable the label by disabling all other buttons
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "Boolean Label" :disable)
    ;; this group label is now disabled
    (group-label-disabled? "Group Label 2")
    ;; is this disabled in the review view too?
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review) :delay 50)
    (b/wait-until #(= []
                      (group-sub-short-labels-review "Group Label 2")))
    (is (= []
           (group-sub-short-labels-review "Group Label 2")))
    ;; now re-enable this label
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "Boolean Label" :enable)
    (b/wait-until #(= ["Boolean Label"]
                      (group-sub-short-labels "Group Label 2")))
    (is (= ["Boolean Label"]
           (group-sub-short-labels "Group Label 2")))
    ;; re-enable string label
    (toggle-enable-disable-sub-label-button "Group Label 2" "String Label" :enable)
    (b/wait-until #(= ["Boolean Label" "String Label"]
                      (group-sub-short-labels "Group Label 2")))
    (is (= ["Boolean Label" "String Label"]
           (group-sub-short-labels "Group Label 2")))
    ;; check that this appears now in the review
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review) :delay 50)
    (b/wait-until #(= ["Boolean Label" "String Label"]
                      (group-sub-short-labels-review "Group Label 2")))
    (is (= ["Boolean Label" "String Label"]
           (group-sub-short-labels-review "Group Label 2"))))
  :cleanup (b/cleanup-test-user! :email (:email test-user)))

(deftest-browser group-labels-in-depth
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-labels-in-depth)"
   project-id (atom nil)
   {:keys [user-id email]} test-user
   include-label-value true
   invalid-value "Invalid Value"
   group-label-definition {:value-type "group"
                           :short-label "Group Label"
                           :definition
                           {:multi? true
                            :labels [{:value-type "boolean"
                                      :short-label "Boolean Label"
                                      :question "Is this true or false?"
                                      :definition {:inclusion-values [true]}
                                      :required true
                                      :value true}
                                     {:value-type "string"
                                      :short-label "String Label"
                                      :question "What value is present for Foo?"
                                      :definition
                                      {:max-length 160
                                       :examples ["foo" "bar" "baz" "qux"]
                                       :multi? true
                                       :regex "^([a-z0-9]{5,})$"}
                                      :required true
                                      :value "Baz"}
                                     {:value-type "categorical"
                                      :short-label "Categorical Label"
                                      :question "Does this label fit within the categories?"
                                      :definition
                                      {:all-values ["Foo" "Bar" "Baz" "Qux"]
                                       :inclusion-values ["Foo" "Bar"]
                                       :multi? false}
                                      :required true
                                      :value "Qux"}]}}]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (reset! project-id (b/current-project-id))
    ;; PubMed search input
    (b/click (xpath "//a[contains(text(),'PubMed Search')]"))
    (pubmed/search-pubmed "foo bar")
    (b/click x/import-button-xpath)
    (b/wait-until-loading-completes :pre-wait 100 :inactive-ms 100 :loop 3
                                    :timeout 10000 :interval 30)
    (is (b/exists? (xpath "//span[@class='unique-count' and contains(text(),'6')]")))
    ;; create new labels
    (log/info "Creating Group Label Definitions")
    (nav/go-project-route "/labels/edit")
    (dlabels/define-group-label group-label-definition)
    ;; make sure the labels are in the correct order
    (is (= (->> group-label-definition :definition :labels (mapv :short-label))
           (group-sub-short-labels "Group Label")))
    ;; review an article
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review) :delay 50)
    (b/click x/review-labels-tab)
    (b/wait-until-displayed
     (group-label-div-with-name (:short-label group-label-definition)))
    ;; we shouldn't have any labels for this project
    (is (empty? (labels/query-public-article-labels @project-id)))
    (log/info "Testing required and regex match")
    ;; can't save
    (b/exists? ".ui.button.save-labels.disabled")
    ;; the Group Label allows for multiple labels to be created
    (b/exists? (add-group-label-review "Group Label"))
    ;; set the labels, but skip String Label, make sure we can't save
    (ra/set-article-answers (conj (->> group-label-definition :definition :labels
                                       (filterv #(not= "String Label" (:short-label %))))
                                  (merge ra/include-label-definition
                                         {:value include-label-value}))
                            :save? false)
    (b/exists? ".ui.button.save-labels.disabled")
    (b/set-input-text (ra/string-input-xpath "String Label") "foo")
    (ra/have-errors? [invalid-value])
    (b/exists? ".ui.button.save-labels.disabled")
    (b/set-input-text (ra/string-input-xpath "String Label") "foo12")
    (ra/have-errors? [])
    ;; Add another Group Label
    (b/click (add-group-label-review "Group Label"))
    ;; we need to:
    ;; Allow for multiple-valued string labels
    ;; set-article-answers should allow a sub label
    ;; e.g. 'Group Label 1', Group Label 2'
    (ra/set-article-answers [{:short-label "Boolean Label"
                              :value false
                              :value-type "boolean"}
                             {:short-label "String Label"
                              :value "bar34"
                              :value-type "string"}
                             {:short-label "Categorical Label"
                              :value "Foo"
                              :value-type "categorical"}]
                            :save? false
                            :xpath (group-label-instance "Group Label 2"))
    ;; add a second value to the string label
    (b/set-input-text (x/xpath (group-label-instance-sub-label "Group Label 2" "String Label")
                               "/descendant::input[@type='text'][2]")
                      "baz56")
    ;; check that the inclusion is working correctly for the boolean label is correct
    (b/exists? (x/xpath (group-label-instance-sub-label "Group Label 1" "Boolean Label")
                        "//i[contains(@class,'plus') and contains(@class,'green')]"))
    (b/exists? (x/xpath (group-label-instance-sub-label "Group Label 2" "Boolean Label")
                        "//i[contains(@class,'minus') and contains(@class,'orange')]"))
    ;; check that the inclusion is working correctly for categorical label
    (b/exists? (x/xpath (group-label-instance-sub-label "Group Label 1" "Categorical Label")
                        "//i[contains(@class,'minus') and contains(@class,'orange')]"))
    (b/exists? (x/xpath (group-label-instance-sub-label "Group Label 2" "Categorical Label")
                        "//i[contains(@class,'plus') and contains(@class,'green')]"))
    ;; add a group label instance, then just delete it
    (b/click (add-group-label-review "Group Label"))
    (b/exists? (x/xpath (group-label-instance "Group Label 3")))
    (b/click (x/xpath (group-label-instance "Group Label 3") "//i[contains(@class,'delete')]"))
    (b/wait-until #(not (taxi/exists? (x/xpath (group-label-instance "Group Label 3")))))
    (is (not (taxi/exists? (x/xpath (group-label-instance "Group Label 3")))))
    ;; 
    (b/click ".button.save-labels" :delay 30 :displayed? true)
    (b/wait-until-loading-completes :pre-wait (if (test/remote-test?) 150 30) :loop 2)
    (some-> (b/current-project-id)
            (db/clear-project-cache))
    ;; check the article
    (nav/go-project-route "/articles" :wait-ms 50)
    (b/click "a.article-title")
    (b/wait-until-displayed ".ui.button.change-labels")
    (is (= include-label-value
           (-> (str (:short-label ra/include-label-definition) "?")
               ra/label-button-value read-string boolean)))
    ;; check boolean, string and categorical values
    (is (= "true"
           (group-label-button-value "Boolean Label" "1")))
    (is (= "false"
           (group-label-button-value "Boolean Label" "2")))
    (is (= "foo12"
           (group-label-button-value "String Label" "1")))
    (is (= "bar34, baz56"
           (group-label-button-value "String Label" "2")))
    (is (= "Qux"
           (group-label-button-value "Categorical Label" "1")))
    (is (= "Foo"
           (group-label-button-value "Categorical Label" "2")))
    (log/info "Testing multiple Group Labels"))
  :cleanup (b/cleanup-test-user! :email (:email test-user)))

(deftest-browser consistent-label-ordering
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (consistent-label-ordering)"
   group-label-definition {:value-type "group"
                           :short-label "Foo"
                           :definition
                           {:labels [{:value-type "boolean"
                                      :short-label "Alpha"
                                      :question "Is this an alpha?"}
                                     {:value-type "categorical"
                                      :short-label "Bravo"
                                      :question "Is this a bravo?"
                                      :definition
                                      {:all-values ["one" "two" "three"]}}
                                     {:value-type "string"
                                      :short-label "Charlie"
                                      :question "Is this a Charlie?"
                                      :required true}]}}]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    ;; create new labels
    (nav/go-project-route "/labels/edit")
    (dlabels/define-group-label group-label-definition)
    ;; order is correct
    (is (= ["Alpha" "Bravo" "Charlie"]
           (group-sub-short-labels "Foo")))
    ;; add another label
    (b/click (edit-group-label-button "Foo"))
    (b/click (x/xpath (group-label-edit-form "Foo") "//button[contains(text(),'Add String Label')]"))
    (dlabels/set-label-definition (x/xpath "(//div[contains(@class,'define-group-label')]//form[contains(@class,'define-label')])[" 4 "]")
                                  {:value-type "string"
                                   :short-label "Delta"
                                   :question "Is this a Delta?"
                                   :required true})
    (b/click dlabels/save-button)
    (is (= ["Alpha" "Bravo" "Charlie" "Delta"]
           (group-sub-short-labels "Foo")))
    ;; add another label
    (b/click (edit-group-label-button "Foo"))
    (b/click (x/xpath (group-label-edit-form "Foo") "//button[contains(text(),'Add Boolean Label')]"))
    (is (= ["Alpha" "Bravo" "Charlie" "Delta" ""]
           (group-label-edit-form-sub-labels "Foo"))))
  :cleanup (b/cleanup-test-user! :email (:email test-user)))
;; delete all labels

#_(do (-> (hsql/delete-from :label) (where [:not= :short_label "Include"]) db/do-execute)
      (sysrev.db.core/clear-project-cache @project-id))

;; delete the project completely
;; (sysrev.test.browser.core/delete-test-user-projects! 2)
