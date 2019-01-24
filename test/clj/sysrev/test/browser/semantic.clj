(ns sysrev.test.browser.semantic
  (:require [clj-webdriver.taxi :as taxi]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :refer [xpath]]))

(defn check-for-error-message [error-message]
  (taxi/exists?
   (xpath
    "//div[contains(@class,'negative') and contains(@class,'message') and contains(text(),'"
    error-message "')]")))
