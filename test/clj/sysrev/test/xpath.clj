(ns sysrev.test.xpath
  (:require [clojure.string :as str]))

(defn contains-class
  "https://stackoverflow.com/a/34779105"
  [class-name]
  (str "contains(concat(\" \", normalize-space(@class), \" \"), \" " class-name " \")"))

(def review-labels-tab
  (str "//div[contains(@class,'review-interface')]"
       "//a[contains(text(),'Labels')]"))

(defn project-menu-item [item-class]
  (str ".ui.menu.project-menu a.item." (name item-class)))

(def save-button
  "//button[contains(text(),'Save')]")

(def add-boolean-label-button
  "//button[contains(text(),'Add Boolean Label')]")

(def add-string-label-button
  "//button[contains(text(),'Add String Label')]")

(def add-categorical-label-button
  "//button[contains(text(),'Add Categorical Label')]")

(def add-annotation-label-button
  "//button[contains(text(),'Add Annotation Label')]")

(defn field-input-xpath
  "Searches within `xpath-parent` for an input element inside a div.field.<field-class>"
  [xpath-parent field-class & {:keys [input-type]}]
  (str xpath-parent
       (format "/descendant::div[contains(@class,'field') and contains(@class,'%s')]"
               field-class)
       "/descendant::input"
       (if (nil? input-type) ""
           (format "[@type='%s']" input-type))))
