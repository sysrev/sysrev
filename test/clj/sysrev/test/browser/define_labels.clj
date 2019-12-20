(ns sysrev.test.browser.define-labels
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.browser.navigate :as nav]))

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
  (->> (taxi/find-elements (xpath "//div[contains(@class,'error')]"
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
  (b/click save-button :delay 20))

(defn discard-label []
  (b/click discard-button :delay 20))

(defn field-input-xpath
  "Searches within `xpath-parent` for an input element inside a div.field.<field-class>"
  [xpath-parent field-class & {:keys [input-type]}]
  (x/xpath xpath-parent
           (format "/descendant::div[contains(@class,'field') and contains(@class,'%s')]"
                   field-class)
           "/descendant::input"
           (if (nil? input-type) ""
               (format "[@type='%s']" input-type))))

(defn set-checkbox-button
  "When selected? is true, set checkbox defined by xpath to 'on',
  otherwise if selected? is false, set checkbox button to 'off'"
  [xpath selected?]
  (when-not (= (boolean selected?) (taxi/selected? (x/xpath xpath)))
    (b/click (x/xpath xpath))))

(defn value-for-inclusion-checkbox [xpath inclusion-value]
  (x/xpath xpath
           "/descendant::" (label-name-xpath "Inclusion value")
           "/parent::div/"
           "descendant::label[contains(text(),'" inclusion-value "')]"
           "/parent::div[contains(@class,'checkbox')]/input[@type='checkbox']"))

(defn set-boolean-inclusion
  "When include? is true, set check box to 'Yes', when false, set to 'No'"
  [xpath include?]
  (let [checkbox #(value-for-inclusion-checkbox xpath (if % "Yes" "No"))]
    (when (or (and include? (taxi/selected? (checkbox (not include?))))
              (and (not include?) (taxi/selected? (checkbox (not include?)))))
      (b/click (checkbox include?)))))

(defn set-boolean-label-definition
  [xpath {:keys [question short-label required definition]
          :or {question "" short-label "" required false}}]
  (let [{:keys [inclusion-values] :or {inclusion-values [true]}} definition
        field-path #(field-input-xpath xpath (str "field-" %))]
    (b/set-input-text (field-path "short-label") short-label)
    (b/set-input-text (field-path "question") question)
    (set-checkbox-button (field-path "required") required)
    (set-checkbox-button (field-path "inclusion") (seq inclusion-values))
    (when (seq inclusion-values)
      (set-boolean-inclusion xpath (first inclusion-values)))))

(defn set-string-label-definition
  [xpath {:keys [question short-label required definition]
          :or {question "" short-label "" required false}}]
  (let [{:keys [examples max-length multi?]
         :or {examples [] max-length ""}} definition
        field-path #(field-input-xpath xpath (str "field-" %))]
    (b/set-input-text (field-path "short-label") short-label)
    (set-checkbox-button (field-path "required") required)
    (set-checkbox-button (field-path "multi") multi?)
    (b/set-input-text (field-path "question") question)
    (b/set-input-text (field-path "max-length") (str max-length))
    (b/set-input-text (field-path "examples") (str/join "," examples) :delay 30)))

(defn set-categorical-label-definition
  [xpath {:keys [question short-label required consensus definition]
          :or {question "" short-label "" required false}}]
  (let [{:keys [all-values inclusion-values]
         :or {all-values [] inclusion-values []}} definition
        field-path #(field-input-xpath xpath (str "field-" %))]
    (b/set-input-text (field-path "short-label") short-label)
    (set-checkbox-button (field-path "required") required)
    (set-checkbox-button (field-path "consensus") consensus)
    (b/set-input-text (field-path "question") question)
    (b/set-input-text (field-path "all-values") (str/join "," all-values) :delay 30)
    (b/wait-until #(= (taxi/value (field-path "all-values"))
                      (str/join "," all-values)))
    (set-checkbox-button (field-path "inclusion") (seq inclusion-values))
    (doseq [value inclusion-values]
      (let [inclusion-checkbox (value-for-inclusion-checkbox xpath value)
            included? (contains? (set inclusion-values) value)]
        ;; each time a selection is made, the checkboxes
        ;; are re-rendered. Need to make sure it is present
        ;; before setting inclusion value
        (b/wait-until-exists inclusion-checkbox)
        (when (not= included? (taxi/selected? inclusion-checkbox))
          (b/click inclusion-checkbox))))))

(defn add-label-button [value-type]
  (condp = value-type
    "boolean"      add-boolean-label-button
    "string"       add-string-label-button
    "categorical"  add-categorical-label-button))

(defn set-label-definition
  "Set definition for label using browser interface."
  [xpath {:keys [value-type] :as label-map}]
  (let [xpath (x/xpath xpath)
        set-definition (condp = value-type
                         "boolean"      set-boolean-label-definition
                         "string"       set-string-label-definition
                         "categorical"  set-categorical-label-definition)]
    (b/wait-until-displayed xpath)
    (set-definition xpath label-map)))

(defn define-label
  "Create a new label definition using browser interface."
  [{:keys [value-type] :as label-map}]
  (let [new-xpath "//div[contains(@id,'new-label-')]"]
    (log/info "creating label definition")
    (nav/go-project-route "/labels/edit" :silent true)
    (b/click (add-label-button value-type))
    (set-label-definition new-xpath label-map)
    (b/click save-button)
    (b/wait-until-loading-completes :pre-wait true)
    (is (empty? (get-all-error-messages)))))

(defn label-definition-div [label-id]
  (xpath (format "//div[@id='%s']" label-id)))

(defn edit-label-button [label-id]
  (xpath (label-definition-div label-id)
         "//div[contains(@class,'edit-label-button')]"))

(defn edit-label
  "Edit an existing label definition using browser interface."
  [label-id label-map]
  (log/info "editing label definition")
  (nav/go-project-route "/labels/edit" :silent true)
  (b/click (edit-label-button label-id))
  (set-label-definition (label-definition-div label-id) label-map)
  (b/click save-button)
  (b/wait-until-loading-completes :pre-wait true)
  (is (empty? (get-all-error-messages))))
