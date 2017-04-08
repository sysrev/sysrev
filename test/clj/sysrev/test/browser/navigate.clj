(ns sysrev.test.browser.navigate
  (:require
   [clojure.test :refer :all]
   [clojure.spec :as s]
   [clojure.spec.test :as t]
   [clojure.tools.logging :as log]
   [clj-webdriver.taxi :as taxi]
   [sysrev.test.core :refer [default-fixture completes?]]
   [sysrev.test.browser.core :refer
    [webdriver-fixture-once webdriver-fixture-each go-route
     test-login]]
   [clojure.string :as str]
   [sysrev.db.users :refer [delete-user create-user]]))

(defn log-in [& [email password]]
  (let [email (or email (:email test-login))
        password (or password (:password test-login))]
    (go-route "/")
    (taxi/click "a[href*='login']")
    (taxi/input-text "input[name='email']" email)
    (taxi/input-text "input[name='password']" password)
    (taxi/click "button[name='submit']")
    (Thread/sleep 1000)))

(defn register-user [& [email password]]
  (let [email (or email (:email test-login))
        password (or password (:password test-login))]
    (go-route "/register")
    (taxi/input-text "input[name='email']" email)
    (taxi/input-text "input[name='password']" password)
    (taxi/click "button[name='submit']")
    (Thread/sleep 1000)))
