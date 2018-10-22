(ns sysrev.test.browser.xpath
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
  [search-term & xpath-rest]
  (apply xpath
         "//span[contains(@class,'import-label') and text()='"
         search-term
         "']/ancestor::div[@class='project-source']"
         xpath-rest))

(defn search-term-delete
  "Given a search term, return the xpath corresponding to its delete button"
  [search-term]
  (search-source
   search-term
   "/descendant::div[contains(@class,'button') and contains(text(),'Delete')]"))

(defn project-title-value [name]
  (xpath "//span[contains(@class,'project-title') and text()='" name "']"))

(def project-sources-list
  (xpath "//div[@id='project-sources']"
         "/descendant::div[contains(@class,'project-sources-list')]"))

(def project-source
  (xpath "//div[@class='project-sources-list']"
         "//ancestor::div[@id='project-sources']"
         "/descendant::div[@class='project-source']"))

(def pubmed-search-input
  (xpath "//input[contains(@placeholder,'PubMed Search...')]"))

(def pubmed-search-form
  (xpath "//form[@id='search-bar']"))

(def create-project-text
  (xpath "//h4[contains(text(),'Create a New Project')]"))

(defn match-text [element text]
  (xpath (format "//%s[text()='%s']" element text)))

(def review-annotator-tab
  {:xpath (str "//div[contains(@class,'review-interface')]"
               "//a[contains(text(),'Annotations')]")})

(def review-labels-tab
  {:xpath (str "//div[contains(@class,'review-interface')]"
               "//a[contains(text(),'Labels')]")})

(def enable-sidebar-button
  {:xpath "//div[contains(@class,'button') and contains(text(),'Enable Sidebar')]"})

(def disable-sidebar-button
  {:xpath "//div[contains(@class,'button') and contains(text(),'Disable Sidebar')]"})
