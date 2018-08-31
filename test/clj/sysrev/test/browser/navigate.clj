(ns sysrev.test.browser.navigate
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :refer [default-fixture completes?]]
            [sysrev.test.browser.core :as browser :refer
             [webdriver-fixture-once webdriver-fixture-each go-route test-login
              deftest-browser]]
            [clojure.string :as str]
            [sysrev.db.users :refer [delete-user create-user]]))

(defn log-out []
  (browser/click "a#log-out-link"
                 :if-not-exists :skip
                 :delay 300))

(defn log-in [& [email password]]
  (let [email (or email (:email test-login))
        password (or password (:password test-login))]
    (log/info "logging in")
    (browser/init-route "/")
    (log-out)
    (browser/go-route "/login")
    (browser/set-input-text "input[name='email']" email :delay 50)
    (browser/set-input-text "input[name='password']" password :delay 50)
    (browser/click "button[name='submit']" :delay 300)
    (browser/go-route "/")))

(defn register-user [& [email password]]
  (let [email (or email (:email test-login))
        password (or password (:password test-login))]
    (log/info "registering user")
    (browser/init-route "/")
    (log-out)
    (browser/go-route "/register")
    (browser/set-input-text "input[name='email']" email :delay 100)
    (browser/set-input-text "input[name='password']" password :delay 100)
    (browser/click "button[name='submit']" :delay 1500)
    (browser/go-route "/")))

(defn open-first-project []
  (browser/go-route "/select-project")
  (browser/click
   {:xpath "//div[contains(@class,'projects-list')]/descendant::a[contains(@class,'segment')]"}
   :delay 500))
