(ns sysrev.test.browser.group-labels
  (:require [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [honeysql.helpers :as hsql :refer [select from where]]
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
  [short-label]
  (taxi/text (xpath "//table/tbody/tr/td[count(//table/thead/tr/th[.='" short-label "']/preceding-sibling::th)+1]")))

(defn group-sub-short-labels
  "Given a group label short-label, return the sub short-labels"
  [short-label]
  (->> (x/xpath "//span[contains(text(),'" short-label "')]/ancestor::div[contains(@class,'define-label-item')][1]//span[contains(@class,'short-label')]")
       taxi/find-elements
       (map taxi/text)
       (into [])))

(deftest-browser group-labels-happy-path
  (and (test/db-connected?) (not test/remote-test?)) test-user
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
    (b/click dlabels/add-group-label-button)
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
                                         {:value include-label-value})))
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
          group-label-value (fn [short-label] (->>
                                               (get-in group-label-definition [:definition :labels])
                                               (medley.core/find-first #(= (:short-label %) short-label)) :value))
          test-short-label-answer (fn [short-label]
                                    (is (= (group-label-value short-label)
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
      (is (= (str (group-label-value "Boolean Label"))
             (group-label-button-value "Boolean Label")))
      ;; check a string value
      (is (= (group-label-value "String Label")
             (group-label-button-value "String Label")))
      ;; check a categorical value
      (is (= (group-label-value "Categorical Label")
             (group-label-value "Categorical Label")))))
  :cleanup (b/cleanup-test-user! :email (:email test-user)))

;; delete all labels
#_(do (-> (hsql/delete-from :label) (where [:not= :short_label "Include"]) db/do-execute)
      (sysrev.db.core/clear-project-cache @project-id))
