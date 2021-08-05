(ns sysrev.test.browser.group-labels
  (:require [clojure.string :as str]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clojure-csv.core :as csv]
            [clj-webdriver.taxi :as taxi]
            [medley.core :as medley]
            [ring.mock.request :as mock]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.label.core :as label]
            [sysrev.project.core :as project]
            [sysrev.project.member :refer [add-project-member set-member-permissions]]
            [sysrev.source.import :as import]
            [sysrev.web.core :refer [sysrev-handler]]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.define-labels :as dlabels]
            [sysrev.test.browser.label-settings :refer [switch-user include-full conflicts resolved]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.orgs :as orgs]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.pubmed :as pubmed]
            [sysrev.test.browser.review-articles :as ra]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.sources :refer [unique-count-span]]
            [sysrev.test.web.routes.utils :refer [route-response-fn]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn group-label-div-with-name [short-label]
  (xpath "//div[contains(@id,'group-label-input-') and contains(text(),'" short-label "')]"))

(defn sub-label-col-xpath
  "Given a short-label, return the nth col underneath it"
  [short-label nth]
  (xpath "(//div[@id='group-label-editor']//table/tbody/tr/td["
         (xpath "count("
                "//div[@id='group-label-editor']//table/thead/tr"
                "/th[.='" short-label "']"
                "/preceding-sibling::th"
                ")+1")
         "])[" nth "]"))

(def add-blank-row-button "div#add-group-label-instance")

(defn delete-row-icon [nth]
  (xpath "//table/tbody/tr[" nth "]/td//i[contains(@class,'delete')]"))

(defn set-sub-group-label [nth {:keys [value-type short-label value]}]
  (let [col (sub-label-col-xpath short-label nth)]
    (cond (or (= value-type "boolean")
              (= value-type "string"))
          (do (b/click col)
              (b/set-input-text-per-char (xpath col "//input") (str value))
              (taxi/send-keys (xpath col "//input") org.openqa.selenium.Keys/ENTER)
              ;; hack - the search bar is still available for input, if 'input' defined in the let is used then it causes an error because taxi/send-keys returns the element passed to it and it won't exist anymore.
              (taxi/send-keys "input#search-sysrev-bar" org.openqa.selenium.Keys/TAB))
          (= value-type "categorical")
          (do (b/click col)
              (b/clear (xpath col "//input"))
              (doall (map (fn [v]
                            (b/set-input-text-per-char (xpath col "//input") (str v))
                            (taxi/send-keys (xpath col "//input")
                                            org.openqa.selenium.Keys/ENTER)) value))
              (taxi/send-keys "input#search-sysrev-bar" org.openqa.selenium.Keys/TAB)))))

(defn set-group-label-row [nth {:keys [short-label definition]}]
  (let [{:keys [labels]} definition
        group-label-editor (xpath "//div[@id='group-label-editor']"
                                  "/descendant::*[contains(text(),'" short-label "')]")]
    (when-not (taxi/exists? group-label-editor)
      (b/click (group-label-div-with-name short-label)))
    (b/wait-until-exists group-label-editor)
    (b/click add-blank-row-button)
    (mapv (partial set-sub-group-label nth) labels)
    nil))

;; https://stackoverflow.com/questions/14745478/how-to-select-table-column-by-column-header-name-with-xpath
;; note: this is fairly hacky, won't work with multiple values
(defn group-label-button-value
  [short-label ith]
  (taxi/text
   (xpath
    "//*[contains(@class,'group-label-values')]//*[@data-rowindex="
    (dec (Long/parseLong ith))
    " and @aria-colindex="
    "count(//*[contains(@class,'group-label-values')]"
    "//*[contains(@class,'MuiDataGrid-colCell') and .='" short-label "']"
    "/preceding-sibling::div)"
    "]")))

(defn group-label-div
  "Given a short-label name for group, return its root xpath"
  [short-label]
  (xpath "//div[contains(@class,'define-label-item') and "
         "@data-short-label='" short-label "']"))

(defn sub-group-label-div
  "Given a root short-label for a group and its sublabel short-label, return its root xpath"
  [root-short-label sub-short-label]
  (xpath (group-label-div root-short-label)
         "//div[contains(@class,'sub-label') and "
         "@data-short-label='" sub-short-label "']"))

(defn group-sub-short-labels
  "Given a group label short-label, return the sub short-labels"
  [root-short-label]
  (b/get-elements-text (xpath (group-label-div root-short-label)
                              "//div[contains(@class,'sub-label')]"
                              "//span[contains(@class,'short-label')]")))

(defn group-sub-short-labels-review
  "In the review interface, return the sub short-labels"
  []
  (->> (b/get-elements-text "thead > tr#sub-labels > th")
       (remove str/blank?)))

(defn edit-group-label-button
  [root-short-label]
  (xpath "//div[contains(@class,'group-label')]"
         "//div[contains(@class,'edit-label-button') and "
         "@data-short-label='" root-short-label "']"))

(defn group-label-edit-form
  [root-short-label]
  (xpath "//div[contains(@class,'label-item') and contains(@class,'group-label')"
         " and @data-short-label='" root-short-label "']"))

(defn group-label-edit-form-sub-labels
  "Given a group label short-label, return the sub short-label values of
  the input forms in an edit group label form."
  [root-short-label]
  (mapv taxi/value (taxi/find-elements
                    (xpath (group-label-edit-form root-short-label)
                           "//div[contains(@class,'sub-labels-edit-form')]"
                           "//div[contains(@class,'field-short-label')]/input"))))

(defn toggle-enable-disable-sub-label-button
  "If enabled is :enable, enable the button, if :disable, disable it"
  [root-short-label sub-short-label enabled]
  (b/click (edit-group-label-button root-short-label))
  (b/click (xpath (group-label-edit-form root-short-label)
                    "//input[@value='" sub-short-label "']"
                    "/ancestor::form"
                    "//button[contains(text(),'" (condp = enabled
                                                   :enable "Enable"
                                                   :disable "Disable")
                    "')]")))

(defn move-short-label
  "Move the short-label in the direction, which is :up or :down"
  [root-short-label sub-short-label direction]
  (b/click (xpath
            (sub-group-label-div root-short-label sub-short-label)
            "//div[contains(text(),'Move " (condp = direction
                                             :up "Up"
                                             :down "Down") "')]")))

(defn group-label-disabled?
  [root-short-label]
  (and (b/exists? (xpath "//h4[contains(text(),'Disabled Labels')]"))
       (b/exists? (xpath (group-label-div root-short-label)
                         "//div[text()='There are disabled labels, edit label to view them']"))))

(defn group-label-value
  "In a group label answer, get the short-label values"
  [short-label group-label-definition]
  (let [{:keys [value]} (->> (get-in group-label-definition [:definition :labels])
                             (medley/find-first #(= (:short-label %) short-label)))]
    (if (vector? value)
      (str/join ", " value)
      value)))

(deftest-browser group-labels-happy-path
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-labels-happy-path-test)"
   project-id (atom nil)
   {:keys [user-id email]} test-user
   include-label-value true
   pm-count (count (pubmed/test-search-pmids "foo bar"))
   group-label-definition {:value-type "group"
                           :short-label "Group Label 1"
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
                                      :value ["Foo" "Qux"]}]}}]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (reset! project-id (b/current-project-id))
    ;; PubMed search input
    (b/select-datasource "PubMed")
    (pubmed/import-pubmed-search-via-db "foo bar")
    (is (b/exists? (unique-count-span pm-count)))
    ;; create new labels
    (log/info "Creating Group Label Definitions")
    (nav/go-project-route "/labels/edit")
    (dlabels/define-group-label group-label-definition)
    ;; make sure the labels are in the correct order
    (is (= (->> group-label-definition :definition :labels (mapv :short-label))
           (group-sub-short-labels "Group Label 1")))
    ;; review an article
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review))
    (b/click x/review-labels-tab)
    (b/wait-until-displayed
     (group-label-div-with-name (:short-label group-label-definition)))
    ;; we shouldn't have any labels for this project
    (is (empty? (label/query-public-article-labels @project-id)))
    ;; set the include label
    (ra/set-label-answer (merge ra/include-label-definition
                                {:value include-label-value}))
    ;; set the labels
    (set-group-label-row 1 group-label-definition)
    (is (b/exists? (xpath "//*[contains(text(),'"
                          (:short-label group-label-definition) "')]")))
    (b/click ".button.save-labels" :displayed? true)
    ;;verify we are on the next article
    (is (b/exists? ".ui.button.save-labels.disabled"))
    ;; check in the database for the labels
    (is (= 1 (count (label/query-public-article-labels @project-id))))
    (log/info "checking label values from db")
    (let [ ;; this is not yet generalized
          [article-id] (keys (label/query-public-article-labels @project-id))
          label->id (q/find :label {:project-id @project-id}
                            :label-id, :index-by :short-label)
          group-label-settings (-> (ra/short-label-answer @project-id article-id user-id
                                                          "Group Label 1")
                                   :labels (get "0"))
          group-label-setting (fn [short-label]
                                (let [answer (->> short-label
                                                  (get label->id)
                                                  (get group-label-settings))]
                                  (cond->> answer
                                    (vector? answer) (str/join ", "))))
          test-short-label-answer (fn [short-label]
                                    (is (= (group-label-value short-label group-label-definition)
                                           (group-label-setting short-label))))]
      ;; these are just checks in the database
      (is (true? (ra/short-label-answer @project-id article-id user-id "Include")))
      (test-short-label-answer "Boolean Label")
      (test-short-label-answer "Categorical Label")
      (test-short-label-answer "String Label")
      (log/info "checking label values from editor")
;;;; Let's check the actual UI for this
      (nav/go-project-route "/articles")
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
             (group-label-button-value "Categorical Label" "1"))))))

(deftest-browser group-labels-error-handling-test
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-labels-error-handling-test)"
   {:keys [user-id email]} test-user
   project-id (atom nil)
   pm-count (count (pubmed/test-search-pmids "foo bar"))
   no-display "Display name must be provided"
   sub-label-required "Group label must include at least one sub label"
   question-required "Question text must be provided"
   options-required "Category options must be provided"
   group-label-defintion-1 {:value-type "group"
                            :short-label "Group Label 1"
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
    (b/select-datasource "PubMed")
    (pubmed/import-pubmed-search-via-db "foo bar")
    (is (b/exists? (unique-count-span pm-count)))
    ;; label editing
    (nav/go-project-route "/labels/edit")
    (b/click dlabels/add-group-label-button)
    ;; try to save it, should have some error messages
    (b/click dlabels/save-button)
    (is (ra/have-errors? [no-display sub-label-required]))
    ;; give the label a name, does the no-display message disappear?
    (b/set-input-text "div.field-short-label input" "Group Label 1")
    (b/click dlabels/save-button)
    (is (ra/have-errors? [sub-label-required]))
    ;; add a simple boolean label, do all of the error messages go away?
    (b/click (xpath (dlabels/add-label-button "boolean")
                    "/ancestor::div[contains(@class,'group')]"
                    "/button"))
    (dlabels/set-label-definition (xpath "(//div[contains(@class,'define-group-label')]"
                                         "//form[contains(@class,'define-label')])[" 1 "]")
                                  (first (get-in group-label-defintion-1 [:definition :labels])))
    (b/click dlabels/save-button)
    ;; make sure that we can't move this label
    ;; this test is a little iffy in that it expects something NOT to exist
    (is (not (taxi/exists? (xpath (sub-group-label-div "Group Label 1" "Boolean Label")
                                  "//div[contains(text(),'Move')]"))))
    (is (ra/have-errors? []))
    ;; Make sure that an invalid sub label can't be saved
    (b/click (edit-group-label-button "Group Label 1"))
    (b/click (xpath (dlabels/add-label-button "categorical") "/ancestor::div[contains(@class,'group')]/button"))
    (b/click dlabels/save-button)
    (is (ra/have-errors? [no-display question-required options-required]))
    ;; just discard this
    (b/click (xpath "//button[contains(text(),'Discard')]"))
    (b/click (xpath "(//button[contains(text(),'Cancel')])[2]"))
    ;; all errors are gone
    (is (ra/have-errors? []))
    ;; Let's make a more complicated label and test moving labels up/down and disabling/enabling
    ;; a group label
    (log/info "Testing moving group labels")
    (dlabels/define-group-label group-label-definition-2)
    (b/is-soon (= ["Boolean Label" "String Label" "Categorical Label"]
                  (group-sub-short-labels "Group Label 2")))
    ;; check that the buttons have the correct "Move Down/Move Up" Buttons
    (is (b/exists? (xpath (sub-group-label-div "Group Label 2" "Boolean Label")
                          "//div[contains(text(),'Move Down')]")))
    ;; Move Down doesn't exist
    (is (not (taxi/exists? (xpath (sub-group-label-div "Group Label 2" "Boolean Label")
                                  "//div[contains(text(),'Move Up')]"))))
    (is (b/exists? (xpath (sub-group-label-div "Group Label 2" "String Label")
                          "//div[contains(text(),'Move Up')]")))
    (is (b/exists? (xpath (sub-group-label-div "Group Label 2" "String Label")
                          "//div[contains(text(),'Move Down')]")))
    (is (b/exists? (xpath (sub-group-label-div "Group Label 2" "Categorical Label")
                          "//div[contains(text(),'Move Up')]")))
    (is (not (taxi/exists? (xpath (sub-group-label-div "Group Label 2" "Categorical Label")
                                  "//div[contains(text(),'Move Down')]"))))
    ;; move boolean down, is the order correct?
    (move-short-label "Group Label 2" "Boolean Label" :down)
    ;; are the orders correct?
    (b/is-soon (= ["String Label" "Boolean Label" "Categorical Label"]
                  (group-sub-short-labels "Group Label 2")))
    ;; check to make sure the review editor has them in the correct order
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review))
    (b/click (group-label-div-with-name "Group Label 2"))
    (b/is-soon (= ["String Label" "Boolean Label" "Categorical Label"]
                  (group-sub-short-labels-review)))
    ;; test label disabling String Label
    (log/info "Testing disabling / enabling labels")
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "String Label" :disable)
    (b/is-soon (= ["Boolean Label" "Categorical Label"]
                  (group-sub-short-labels "Group Label 2")))
    ;; is this disabled in the review view too?
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review))
    (b/click (group-label-div-with-name "Group Label 2"))
    (b/is-soon (= ["Boolean Label" "Categorical Label"] (group-sub-short-labels-review)))
    ;; test disabling the Boolean Label
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "Categorical Label" :disable)
    (b/is-soon (= ["Boolean Label"] (group-sub-short-labels "Group Label 2")))
    ;; is this disabled in the review view too?
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review))
    (b/click (group-label-div-with-name "Group Label 2"))
    (b/is-soon (= ["Boolean Label"] (group-sub-short-labels-review)))
    ;; completely disable the label by disabling all other buttons
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "Boolean Label" :disable)
    ;; this group label is now disabled
    (group-label-disabled? "Group Label 2")
    ;; is this disabled in the review view too?
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review))
    (is (not (taxi/exists? (group-label-div-with-name "Group Label 2"))))
    ;; now re-enable this label
    (nav/go-project-route "/labels/edit")
    (toggle-enable-disable-sub-label-button "Group Label 2" "Boolean Label" :enable)
    (b/is-soon (= ["Boolean Label"] (group-sub-short-labels "Group Label 2")))
    ;; re-enable string label
    (toggle-enable-disable-sub-label-button "Group Label 2" "String Label" :enable)
    (b/is-soon (= ["Boolean Label" "String Label"] (group-sub-short-labels "Group Label 2")))
    ;; check that this appears now in the review
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review))
    (b/click (group-label-div-with-name "Group Label 2"))
    (b/is-soon (= ["Boolean Label" "String Label"] (group-sub-short-labels-review)))))

(deftest-browser group-labels-in-depth
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "SysRev Browser Test (group-labels-in-depth)"
   project-id (atom nil)
   pm-count (count (pubmed/test-search-pmids "foo bar"))
   include-label-value true
   invalid-value "Invalid"
   group-label-definition {:value-type "group"
                           :short-label "Group Label 1"
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
                                      :value ["Qux"]}]}}]
  (do
    (nav/log-in (:email test-user))
    (nav/new-project project-name)
    (reset! project-id (b/current-project-id))
    ;; PubMed search input
    (b/select-datasource "PubMed")
    (pubmed/import-pubmed-search-via-db "foo bar")
    (is (b/exists? (unique-count-span pm-count)))
    ;; create new labels
    (log/info "Creating Group Label Definitions")
    (nav/go-project-route "/labels/edit")
    (dlabels/define-group-label group-label-definition)
    ;; make sure the labels are in the correct order
    (is (= (->> group-label-definition :definition :labels (mapv :short-label))
           (group-sub-short-labels "Group Label 1")))
    ;; review an article
    (nav/go-project-route "")
    (b/click (x/project-menu-item :review))
    (b/click x/review-labels-tab)
    (b/wait-until-displayed
     (group-label-div-with-name (:short-label group-label-definition)))
    ;; we shouldn't have any labels for this project
    (is (empty? (label/query-public-article-labels @project-id)))
    (log/info "Testing required and regex match")
    ;; can't save
    (b/exists? ".ui.button.save-labels.disabled")
    ;; set the labels, but skip String Label, make sure we can't save
    (ra/set-label-answer (merge ra/include-label-definition
                                {:value include-label-value}))
    (set-group-label-row 1 (assoc-in
                            group-label-definition [:definition :labels]
                            (->> group-label-definition :definition :labels
                                 (filterv #(not= "String Label" (:short-label %))))))
    (b/exists? ".ui.button.save-labels.disabled")
    (set-sub-group-label 1 {:value-type "string" :short-label "String Label" :value "foo"})
    (b/text-is? (sub-label-col-xpath "String Label" 1) invalid-value)
    (b/exists? ".ui.button.save-labels.disabled")
    (set-sub-group-label 1 {:value-type "string" :short-label "String Label" :value "foo12"})
    (b/text-is? (sub-label-col-xpath "String Label" 1) "foo12")
    ;; Add another Group Label
    (set-group-label-row 2 (assoc-in group-label-definition [:definition :labels]
                                     [{:short-label "Boolean Label"
                                       :value false
                                       :value-type "boolean"}
                                      {:short-label "String Label"
                                       :value "bar34"
                                       :value-type "string"}
                                      {:short-label "Categorical Label"
                                       :value "Foo"
                                       :value-type "categorical"}]))
    ;; check that the inclusion is working correctly for the boolean label is correct
    (b/exists? (xpath (sub-label-col-xpath "Boolean Label" 1)
                      "//div[contains(@class,'green')]"))
    (b/exists? (xpath (sub-label-col-xpath "Boolean Label" 2)
                      "//div[contains(@class,'orange')]"))
    ;; check that the inclusion is working correctly for categorical label
    (b/exists? (xpath (sub-label-col-xpath "Categorical Label" 1)
                      "//div[contains(@class,'orange')]"))
    (b/exists? (xpath (sub-label-col-xpath "Categorical Label" 2)
                      "//div[contains(@class,'green')]"))
    ;; add a group label instance, then just delete it
    (b/click add-blank-row-button)
    (b/text-is? (sub-label-col-xpath "Categorical Label" 3) "Required")
    ;; has to be done twice in order to actually register
    ;(b/click (delete-row-icon 3))
    (b/click (delete-row-icon 3))
    (b/is-soon (not (taxi/exists? (delete-row-icon 3))))
    (b/click ".button.save-labels" :displayed? true)
    (b/wait-until-loading-completes :pre-wait (if (test/remote-test?) 150 30) :loop 2)
    (some-> (b/current-project-id) (db/clear-project-cache))
    ;; check the article
    (nav/go-project-route "/articles")
    (b/click "a.article-title")
    (b/wait-until-displayed ".ui.button.change-labels")
    (is (= include-label-value
           (-> (str (:short-label ra/include-label-definition) "?")
               ra/label-button-value read-string boolean)))
    ;; check boolean, string and categorical values
    (is (= "true"  (group-label-button-value "Boolean Label" "1")))
    (is (= "false" (group-label-button-value "Boolean Label" "2")))
    (is (= "foo12" (group-label-button-value "String Label" "1")))
    (is (= "bar34" (group-label-button-value "String Label" "2")))
    (is (= "Qux"   (group-label-button-value "Categorical Label" "1")))
    (is (= "Foo"   (group-label-button-value "Categorical Label" "2")))))

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
                                      :definition {:all-values ["one" "two" "three"]}}
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
    (is (= ["Alpha" "Bravo" "Charlie"] (group-sub-short-labels "Foo")))
    ;; add another label
    (b/click (edit-group-label-button "Foo"))
    (b/click (xpath (group-label-edit-form "Foo")
                    "//button[contains(text(),'Add String Label')]"))
    (dlabels/set-label-definition
     (xpath "(//div[contains(@class,'define-group-label')]"
            "//form[contains(@class,'define-label')])[" 4 "]")
     {:value-type "string"
      :short-label "Delta"
      :question "Is this a Delta?"
      :required true})
    (b/click dlabels/save-button)
    (is (= ["Alpha" "Bravo" "Charlie" "Delta"] (group-sub-short-labels "Foo")))
    ;; add another label
    (b/click (edit-group-label-button "Foo"))
    (b/click (xpath (group-label-edit-form "Foo")
                    "//button[contains(text(),'Add Boolean Label')]"))
    (is (= ["Alpha" "Bravo" "Charlie" "Delta" ""]
           (group-label-edit-form-sub-labels "Foo")))))

(defn check-status
  [n-full n-conflict n-resolved]
  (nav/go-project-route "" :silent true)
  (b/wait-until-loading-completes :pre-wait true)
  (is (b/exists? include-full))
  (is (= (format "Full (%d)" n-full) (taxi/text include-full)))
  (is (b/exists? conflicts))
  (is (= (format "Conflict (%d)" n-conflict) (taxi/text conflicts)))
  (is (b/exists? resolved))
  (is (= (format "Resolved (%d)" n-resolved) (taxi/text resolved))))

(deftest-browser label-consensus-test
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-id (atom nil)
   label-id-1 (atom nil)
   test-users (mapv #(b/create-test-user :email %)
                    (mapv #(str "user" % "@foo.bar") [1 2]))
   [user1 user2] test-users
   include-label-value true
   project-name "Label Consensus Test (Group Labels)"
   group-label-definition {:value-type "group"
                           :short-label "Group Label 1"
                           :definition
                           {:multi? true
                            :labels [{:value-type "boolean"
                                      :short-label "Boolean Label"
                                      :question "Is this true or false?"
                                      :definition {:inclusion-values [true]}
                                      :required true
                                      :consensus true}
                                     {:value-type "string"
                                      :short-label "String Label"
                                      :question "What value is present for Foo?"
                                      :definition
                                      {:max-length 160
                                       :examples ["foo" "bar" "baz" "qux"]
                                       :multi? true}
                                      :required true}
                                     {:value-type "categorical"
                                      :short-label "Categorical Label"
                                      :question "Does this label fit within the categories?"
                                      :definition
                                      {:all-values ["Foo" "Bar" "Baz" "Qux"]
                                       :inclusion-values ["Foo" "Bar"]
                                       :multi? false}
                                      :required true
                                      :consensus true}]}}]
  (do (nav/log-in (:email test-user))
      ;; create project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      ;; import one article
      (import/import-pmid-vector @project-id {:pmids [25706626]} {:use-future? false})
      (nav/go-project-route "/labels/edit")
      ;; create a group label
      (dlabels/define-group-label group-label-definition)
      ;; make sure the labels are in the correct order
      (is (= (->> group-label-definition :definition :labels (mapv :short-label))
             (group-sub-short-labels "Group Label 1")))
      ;; add users to project
      (doseq [{:keys [user-id]} test-users]
        (add-project-member @project-id user-id))
      (set-member-permissions @project-id (:user-id user1) ["member" "admin"])
      ;; review article from user1
      (switch-user (:email user1) @project-id)
      (nav/go-project-route "/review")
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition [:definition :labels]
                                       [{:short-label "Boolean Label"
                                         :value false
                                         :value-type "boolean"}
                                        {:short-label "String Label"
                                         :value "bar34"
                                         :value-type "string"}
                                        {:short-label "Categorical Label"
                                         :value ["Foo"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      (is (b/exists? ".no-review-articles"))
      ;; review article from user2 (different categorical answer)
      (switch-user (:email user2) @project-id)
      (nav/go-project-route "/review")
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition [:definition :labels]
                                       [{:short-label "Boolean Label"
                                         :value true
                                         :value-type "boolean"}
                                        {:short-label "String Label"
                                         :value "bar34"
                                         :value-type "string"}
                                        {:short-label "Categorical Label"
                                         :value ["Bar"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      (is (b/exists? ".no-review-articles"))
      ;; check for conflict
      (check-status 0 1 0)
      ;; attempt to resolve conflict as admin
      (switch-user (:email test-user) @project-id)
      (nav/go-project-route "/articles")
      (b/click "a.article-title")
      ;; the labels are in conflict
      (is (b/exists? (xpath "//div[contains(@class, 'review-status') and contains(text(),'Conflicting labels')]")))
      (b/click "div.resolve-labels")
      ;; set the user1 labels as correct
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition [:definition :labels]
                                       [{:short-label "Boolean Label"
                                         :value false
                                         :value-type "boolean"}
                                        {:short-label "String Label"
                                         :value "bar34"
                                         :value-type "string"}
                                        {:short-label "Categorical Label"
                                         :value ["Foo"]
                                         :value-type "categorical"}]))
      (b/click ".save-labels")
      ;; article is shown as resolved
      (is (b/exists? (xpath "//div[contains(@class, 'review-status') and contains(text(),'Resolved')]")))
      (b/click ".overview")
      ;; there is only a single resolved label
      (check-status 1 0 1))
  :cleanup (do (some-> @project-id (project/delete-project))
               (doseq [{:keys [email]} test-users]
                 (b/delete-test-user :email email))))

(deftest-browser group-label-paywall
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [test-user (b/create-test-user :email "baz@foo.bar")
   project-name "Group Label Paywall Test"
   project-id (atom nil)
   org-name "Foo Group Label.org"
   org-project-name "Org Group Label Paywall Test"]
  (do (nav/log-in (:email test-user))
      ;; create project
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      ;; paywall in place?
      (nav/go-project-route "/labels/edit")
      (b/exists? "#group-label-paywall")
      ;; let's sign up for user Pro account
      (plans/user-subscribe-to-unlimited (:email test-user))
      ;; go back and see if paywall is in place
      (nav/go-route (str "/p/" @project-id "/labels/edit"))
      ;; now we see the 'Add Group Label' button
      (b/exists? (xpath "//button[contains(text(),'Add Group Label')]"))
      ;; Now, let's make a group
      (orgs/create-org org-name)
      (b/click "#org-projects")
      (orgs/create-project-org org-project-name)
      ;; now let's check that the paywall is lifted
      (nav/go-project-route "/labels/edit")
      (b/exists? (xpath "//button[contains(text(),'Add Group Label')]")))
  :cleanup (b/cleanup-test-user! :email (:email test-user) :groups true))

(deftest-browser group-label-csv-download-test
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [project-name "Group Label CSV Download Test"
   project-id (atom nil)
   test-users (mapv #(b/create-test-user :email %)
                    (mapv #(str "user" % "@foo.bar") [1 2]))
   [user1 user2] test-users
   include-label-value true
   group-label-definition {:value-type "group"
                           :short-label "Alpha"
                           :definition
                           {:multi? true
                            :labels [{:value-type "boolean"
                                      :short-label "Bravo"
                                      :question "What is a bravo?"
                                      :definition {:inclusion-values [true]}
                                      :required true
                                      :consensus true}
                                     {:value-type "string"
                                      :short-label "Charlie"
                                      :question "What is a Charlie?"
                                      :definition
                                      {:max-length 160
                                       :examples ["X-ray" "Yankee" "Zulu"]
                                       :multi? true}
                                      :required true}
                                     {:value-type "categorical"
                                      :short-label "Delta"
                                      :question "Does this label fit within the categories?"
                                      :definition
                                      {:all-values ["four" "five" "six"]
                                       :inclusion-values ["four" "five"]
                                       :multi? false}
                                      :required true
                                      :consensus true}]}}]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      ;; import three articles
      (import/import-pmid-vector @project-id {:pmids [25706626 22716928 9656183]})
      ;; create a group label
      (nav/go-project-route "/labels/edit")
      (dlabels/define-group-label group-label-definition)
      ;; add users to project
      (doseq [{:keys [user-id]} test-users]
        (add-project-member @project-id user-id))
      ;; user1 reviews some article
      (switch-user (:email user1) @project-id)
      (nav/go-project-route "/review")
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition
                                       [:definition :labels]
                                       [{:short-label "Bravo"
                                         :value false
                                         :value-type "boolean"}
                                        {:short-label "Charlie"
                                         :value "X-ray"
                                         :value-type "string"}
                                        {:short-label "Delta"
                                         :value ["four"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition
                                       [:definition :labels]
                                       [{:short-label "Bravo"
                                         :value true
                                         :value-type "boolean"}
                                        {:short-label "Charlie"
                                         :value "Yankee"
                                         :value-type "string"}
                                        {:short-label "Delta"
                                         :value ["five"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition
                                       [:definition :labels]
                                       [{:short-label "Bravo"
                                         :value false
                                         :value-type "boolean"}
                                        {:short-label "Charlie"
                                         :value "Zulu"
                                         :value-type "string"}
                                        {:short-label "Delta"
                                         :value ["six"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      ;; switch users
      (switch-user (:email user2) @project-id)
      (nav/go-project-route "/review")
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition
                                       [:definition :labels]
                                       [{:short-label "Bravo"
                                         :value false
                                         :value-type "boolean"}
                                        {:short-label "Charlie"
                                         :value "X-ray"
                                         :value-type "string"}
                                        {:short-label "Delta"
                                         :value ["four"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition
                                       [:definition :labels]
                                       [{:short-label "Bravo"
                                         :value true
                                         :value-type "boolean"}
                                        {:short-label "Charlie"
                                         :value "Yankee"
                                         :value-type "string"}
                                        {:short-label "Delta"
                                         :value ["five"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      (ra/set-label-answer (merge ra/include-label-definition
                                  {:value include-label-value}))
      (set-group-label-row 1 (assoc-in group-label-definition
                                       [:definition :labels]
                                       [{:short-label "Bravo"
                                         :value false
                                         :value-type "boolean"}
                                        {:short-label "Charlie"
                                         :value "Zulu"
                                         :value-type "string"}
                                        {:short-label "Delta"
                                         :value ["six"]
                                         :value-type "categorical"}]))
      (b/click ".button.save-labels" :displayed? true)
      ;; let's login to our project and download the json
      (switch-user (:email test-user) @project-id)
      (nav/go-project-route "/export")
      ;; the Group Label CSV download exists
      (b/exists? (xpath "//h4[contains(text(),'Group Label CSV')]"))
      ;; download the actual file and check its properties
      (let [group-label-id (q/find-one :label {:project-id @project-id :short-label "Alpha"}
                                       :label-id)
            handler (sysrev-handler)
            route-response (route-response-fn handler)
            _ (get-in (route-response :post "/api/auth/login" test-user)
                      [:result :valid])
            {:keys [download-id filename]}
            (get-in (route-response :post (str "/api/generate-project-export/"
                                               @project-id "/group-label-csv")
                                    {:label-id group-label-id})
                    [:result :entry])
            csv-file (-> (mock/request :get (str "/api/download-project-export/"
                                                 @project-id "/group-label-csv/"
                                                 download-id "/" filename))
                         handler :body slurp)
            parsed-csv-file (csv/parse-csv csv-file)
            csv-data (rest parsed-csv-file)
            answer-data (->> csv-data (map #(take-last 3 %)))]
        ;; there are seven rows, corresponding to the header + 6 data rows
        (is (= 7 (count parsed-csv-file)))
        ;; the data labels are in the correct order
        (is (= ["Bravo" "Charlie" "Delta"]
               (->> parsed-csv-file first (take-last 3))))
        ;; three distinct articles were reviewed
        (is (= 3 (->> csv-data (map #(nth % 0)) distinct count)))
        ;; two distinct reviewers did work
        (is (= 2 (->> csv-data (map #(nth % 1)) distinct count)))
        (is (= 2 (->> csv-data (map #(nth % 2)) distinct count)))
        ;; we have the correct label answers
        (is (= 2 (count (filter #(= ["true" "Yankee" "five"] %) answer-data))))
        (is (= 2 (count (filter #(= ["false" "X-ray" "four"] %) answer-data))))
        (is (= 2 (count (filter #(= ["false" "Zulu" "six"] %) answer-data))))))
  :cleanup (doseq [{:keys [email]} test-users]
             (b/cleanup-test-user! :email email)))
