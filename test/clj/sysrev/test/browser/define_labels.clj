(ns sysrev.test.browser.define-labels
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
            [clojure.tools.logging :as log]))

(def save-button
  (xpath "//button[contains(text(),'Save')]"))
(def disabled-save-button
  (xpath "//button[contains(@class,'disabled') and contains(text(),'Save')]"))
(def discard-button
  (xpath "//button[contains(@class,'labeled') and contains(text(),'Discard')]"))

(def add-boolean-label-button
  (xpath "//button[contains(text(),'Add Boolean Label')]"))
(def add-string-label-button
  (xpath "//button[contains(text(),'Add String Label')]"))
(def add-categorical-label-button
  (xpath "//button[contains(text(),'Add Categorical Label')]"))

(defn get-all-error-messages []
  (->> (taxi/find-elements
        (xpath "//div[contains(@class,'error')]"
               "/descendant::div[contains(@class,'message')"
               " and contains(@class,'red')]"))
       (mapv taxi/text)))

(defn label-name-xpath
  "Given a label-name, return the xpath for it"
  [label-name]
  (xpath "label[contains(text(),'" label-name "')]"))

(defn delete-label [xpath]
  (let [div-id (taxi/attribute (x/xpath xpath) :id)]
    (b/click
     (x/xpath "//div[@id='" div-id "']"
              "/descendant::div[contains(@class,'remove')]"))))

(defn save-label []
  (log/info "saving label definition")
  (b/click save-button :delay 50))

(defn discard-label []
  (b/click discard-button :delay 50))

(defn field-input-xpath [xpath field-class & {:keys [input-type]}]
  "Searches inside xpath for an input element within a div.field
  where the field contains field-class."
  (x/xpath xpath
           (format "/descendant::div[contains(@class,'field') and contains(@class,'%s')]"
                   field-class)
           "/descendant::input"
           (if (nil? input-type) ""
               (format "[@type='%s']" input-type))))

(defn set-checkbox-button
  "When selected? is true, set checkbox defined by xpath to 'on',
  otherwise if selected? is false, set checkbox button to 'off'"
  [xpath selected?]
  (when-not (= selected? (taxi/selected? (x/xpath xpath)))
    (b/click (x/xpath xpath))))

(defn value-for-inclusion-checkbox
  [xpath inclusion-value]
  (x/xpath xpath
           "/descendant::" (label-name-xpath "Inclusion value")
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

(defn set-boolean-label-definition
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
     (field-input-xpath xpath "label-name")
     short-label)
    ;; enter the question
    (b/set-input-text
     (field-input-xpath xpath "label-question")
     question)
    ;; required setting
    (set-checkbox-button
     (field-input-xpath xpath "require-answer")
     required)
    ;; inclusion values
    (set-checkbox-button
     (field-input-xpath xpath "inclusion-criteria")
     (-> inclusion-values empty? not))
    (when (not-empty inclusion-values)
      (set-boolean-inclusion xpath (first inclusion-values)))))

(defn set-string-label-definition
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
     (field-input-xpath xpath "label-name")
     short-label)
    ;; required setting
    (set-checkbox-button
     (field-input-xpath xpath "require-answer")
     required)
    ;; allow multiple values?
    (set-checkbox-button
     (field-input-xpath xpath "allow-multiple")
     multi?)
    ;; enter the question
    (b/set-input-text
     (field-input-xpath xpath "label-question")
     question)
    ;; enter the max length
    (b/set-input-text
     (field-input-xpath xpath "max-length")
     (str max-length))
    ;; Examples
    (b/set-input-text
     (field-input-xpath xpath "examples")
     (str/join "," examples)
     :delay 50)))

(defn set-categorical-label-definition
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
     (field-input-xpath xpath "label-name")
     short-label)
    ;; required setting
    (set-checkbox-button
     (field-input-xpath xpath "require-answer")
     required)
    ;; enter the question
    (b/set-input-text
     (field-input-xpath xpath "label-question")
     question)
    ;; enter the categories
    (b/set-input-text
     (field-input-xpath xpath "categories")
     (str/join "," all-values)
     :delay 50)
    ;;  inclusion values
    (taxi/wait-until
     #(= (taxi/value (field-input-xpath xpath "categories"))
         (str/join "," all-values))
     5000 25)
    (set-checkbox-button
     (field-input-xpath xpath "inclusion-criteria")
     (-> inclusion-values empty? not))
    (when (not-empty inclusion-values)
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
            all-values))))

(defn set-label-definition
  "Set definition for label using browser interface."
  [xpath label-map]
  (let [{:keys [value-type]
         :or {value-type "boolean"}} label-map]
    (b/wait-until-displayed (x/xpath xpath))
    (condp = value-type
      "boolean"     (set-boolean-label-definition xpath label-map)
      "string"      (set-string-label-definition xpath label-map)
      "categorical" (set-categorical-label-definition xpath label-map))))

(defn define-label
  "Create a new label definition using browser interface."
  [label-map]
  (let [{:keys [value-type]} label-map
        [add-label set-values]
        (condp = value-type
          "boolean"     [add-boolean-label-button
                         set-boolean-label-definition]
          "string"      [add-string-label-button
                         set-string-label-definition]
          "categorical" [add-categorical-label-button
                         set-categorical-label-definition])
        new-xpath "//div[contains(@id,'new-label-')]"]
    (nav/go-project-route "/labels/edit")
    (b/click add-label)
    (set-values new-xpath label-map)
    (b/click save-button)))
