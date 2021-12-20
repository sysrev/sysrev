(ns sysrev.test.xpath
  (:require [clojure.string :as str]))

(defn xpath
  "Creates a taxi xpath query map by concatenating values `items`.

   Values in `items` may be string or xpath map.

   For xpath map values, the string value will be extracted and concatenated."
  [& items]
  (letfn [(extract [item]
            (if (and (map? item) (contains? item :xpath))
              (:xpath item) item))]
    {:xpath (->> items (map extract) (str/join))}))

(defn search-source
  "Given a search term, return a string of the xpath corresponding to the
  project-source div"
  [search-term]
  (xpath "//span[contains(@class,'import-label') and text()='" search-term "']"
         "/ancestor::div[@class='project-source']"))

(defn search-term-edit
  "Given a search term, return the xpath corresponding to its delete button"
  [search-term]
  (xpath (search-source search-term)
         "/descendant::div[contains(@class,'button') and contains(text(),'Edit')]"))

(defn search-term-delete
  "Given a search term, return the xpath corresponding to its delete button"
  [search-term]
  (xpath (search-source search-term)
         "/descendant::div[contains(@class,'button') and contains(text(),'Delete')]"))

(defn project-title-value [name]
  (xpath "//span[contains(@class,'project-title') and contains(text(),'" name "')]"))

(def project-source
  (xpath "//div[@class='project-sources-list']"
         "//ancestor::div[@id='project-sources']"
         "/descendant::div[@class='project-source']"))

(defn match-text [element text]
  (xpath (format "//%s[text()='%s']" element text)))

(def review-labels-tab
  (xpath "//div[contains(@class,'review-interface')]"
         "//a[contains(text(),'Labels')]"))

(defn project-menu-item [item-class]
  (str ".ui.menu.project-menu a.item." (name item-class)))

(def import-button-xpath
  (xpath "//button[contains(@class,'button') and contains(text(),'Import')]"))
