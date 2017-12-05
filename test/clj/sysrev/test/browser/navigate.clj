(ns sysrev.test.browser.navigate
  (:require
   [clojure.test :refer :all]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as t]
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
    (go-route "/login")
    (taxi/input-text "input[name='email']" email)
    (taxi/input-text "input[name='password']" password)
    (taxi/click "button[name='submit']")
    (Thread/sleep 1000)))

(defn log-out []
  (taxi/select "a[id='log-out-link']"))

(defn register-user [& [email password]]
  (let [email (or email (:email test-login))
        password (or password (:password test-login))]
    (go-route "/register")
    (taxi/input-text "input[name='email']" email)
    (taxi/input-text "input[name='password']" password)
    (taxi/click "button[name='submit']")
    (Thread/sleep 1000)))
