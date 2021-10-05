(ns sysrev.test.browser.ctgov
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.tools.logging :as log]
            [sysrev.test.browser.core :as b]))

(def ctgov-search-input "form#search-bar.ctgov-search input[type=text]")

(defn search-ctgov
  "Enter and submit a CT.gov search query."
  [query]
  (log/info "running CT.gov search:" (pr-str query))
  (b/wait-until-loading-completes :pre-wait true)
  (when-not (taxi/exists? ctgov-search-input)
    (b/click "a.tab-ctgov"))
  (b/wait-until-displayed ctgov-search-input)
  (Thread/sleep 30)
  (b/click ".ui.button.close-search" :if-not-exists :skip)
  (b/set-input-text ctgov-search-input query)
  (taxi/submit "form#search-bar.ctgov-search")
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))

(defn search-articles
  "Navigate to Articles page and enter a text search."
  [query]
  (log/info "running article search:" (pr-str query))
  (b/click ".project-menu a.item.articles" :displayed? true)
  (b/set-input-text "input#article-search" query)
  (b/wait-until-loading-completes :pre-wait 200 :inactive-ms 50 :timeout 20000))
