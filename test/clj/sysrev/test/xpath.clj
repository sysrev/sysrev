(ns sysrev.test.xpath
  (:require [clojure.string :as str]))

(defn contains-class
  "https://stackoverflow.com/a/34779105"
  [class-name]
  (str "contains(concat(\" \", normalize-space(@class), \" \"), \" " class-name " \")"))

(defn search-source
  "Given a search term, return a string of the xpath corresponding to the
  project-source div"
  [search-term]
  (str "//span[contains(@class,'import-label') and text()='" search-term "']"
         "/ancestor::div[@class='project-source']"))

(defn search-term-edit
  "Given a search term, return the xpath corresponding to its delete button"
  [search-term]
  (str (search-source search-term)
         "/descendant::div[contains(@class,'button') and contains(text(),'Edit')]"))

(defn search-term-delete
  "Given a search term, return the xpath corresponding to its delete button"
  [search-term]
  (str (search-source search-term)
         "/descendant::div[contains(@class,'button') and contains(text(),'Delete')]"))

(defn project-title-value [name]
  (str "//span[contains(@class,'project-title') and contains(text(),'" name "')]"))

(def project-source
  (str "//div[@class='project-sources-list']"
         "//ancestor::div[@id='project-sources']"
         "/descendant::div[@class='project-source']"))

(defn match-text [element text]
  (str (format "//%s[text()='%s']" element text)))

(def review-labels-tab
  (str "//div[contains(@class,'review-interface')]"
         "//a[contains(text(),'Labels')]"))

(defn project-menu-item [item-class]
  (str ".ui.menu.project-menu a.item." (name item-class)))

(def import-button-xpath
   "//button[contains(@class,'button') and contains(text(),'Import')]")

(def save-button
   "//button[contains(text(),'Save')]")

(def discard-button
   "//button[contains(@class,'labeled') and contains(text(),'Discard')]")

(def add-boolean-label-button
  "//button[contains(text(),'Add Boolean Label')]")

(def add-string-label-button
   "//button[contains(text(),'Add String Label')]")

(def add-categorical-label-button
   "//button[contains(text(),'Add Categorical Label')]")

(def add-group-label-button
   "//button[contains(text(),'Add Group Label')]")

(def add-annotation-label-button
  "//button[contains(text(),'Add Annotation Label')]")

(def import-label-button
  "//button[contains(text(),'Import Label')]")

(defn field-input-xpath
  "Searches within `xpath-parent` for an input element inside a div.field.<field-class>"
  [xpath-parent field-class & {:keys [input-type]}]
  (str xpath-parent
       (format "/descendant::div[contains(@class,'field') and contains(@class,'%s')]"
               field-class)
       "/descendant::input"
       (if (nil? input-type) ""
           (format "[@type='%s']" input-type))))
