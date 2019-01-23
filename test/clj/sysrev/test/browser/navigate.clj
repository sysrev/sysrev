(ns sysrev.test.browser.navigate
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.shared.util :as sutil :refer [in? parse-integer]]))

(defn path->full-url [path]
  (let [path (if (empty? path) "/" path)]
    (str (:url (test/get-selenium-config))
         (if (= (nth path 0) \/)
           (subs path 1) path))))

(defn init-route [& [path]]
  (let [full-url (path->full-url path)]
    (log/info "loading:" full-url)
    (taxi/to full-url)
    (b/wait-until-loading-completes :pre-wait 100)
    (b/wait-until-loading-completes :pre-wait 100)
    (taxi/execute-script "sysrev.base.toggle_analytics(false);"))
  nil)

(defn go-route [& [path wait-ms]]
  (let [current (taxi/current-url)
        path (if (empty? path) "/" path)]
    (cond (or (not (string? current))
              (not (str/includes? current (:url (test/get-selenium-config)))))
          (init-route path)
          (not= current (path->full-url path))
          (do (b/wait-until-loading-completes :pre-wait 10)
              (log/info "navigating:" path)
              (taxi/get-url (path->full-url path))
              (b/wait-until-loading-completes :pre-wait (or wait-ms 50))))
    nil))

(defn go-project-route [suburi & [project-id]]
  (when (nil? project-id)
    (taxi/wait-until #(integer? (b/current-project-id)) 2000 25))
  (let [project-id (or project-id (b/current-project-id))]
    (assert (integer? project-id))
    (go-route (str "/p/" project-id suburi))))

(defn log-out []
  (when (taxi/exists? "a#log-out-link")
    (log/info "logging out")
    (b/click "a#log-out-link" :if-not-exists :skip)
    (Thread/sleep 100)))

(defn log-in [& [email password]]
  (let [email (or email (:email b/test-login))
        password (or password (:password b/test-login))]
    (go-route "/")
    (log-out)
    (go-route "/login")
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (Thread/sleep 100)
    (go-route "/")))

(defn register-user [& [email password]]
  (let [email (or email (:email b/test-login))
        password (or password (:password b/test-login))]
    (log-out)
    (go-route "/register")
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (b/wait-until-exists (xpath "//h4[contains(text(),'Create a New Project')]"))
    ))

(defn wait-until-overview-ready []
  (let [overview (xpath "//span[contains(text(),'Overview')]")
        disabled (xpath overview "/ancestor::a[contains(@class,'item disabled')]")]
    (taxi/wait-until #(and (taxi/exists? overview)
                           (not (taxi/exists? disabled)))
                     10000 25)))

(defn new-project [project-name]
  (log/info "creating project" (pr-str project-name))
  (go-route "/")
  (b/set-input-text "input[placeholder='Project Name']" project-name)
  (b/click (xpath "//button[text()='Create']"))
  (Thread/sleep 100)
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath (format "//span[contains(@class,'project-title') and text()='%s']"
                  project-name)
          "//ancestor::div[@id='project']"))
  (b/wait-until-loading-completes :pre-wait 100))

(defn open-project [name]
  (log/info "opening project" (pr-str name))
  (go-route "/")
  (b/click (x/project-title-value name)))

(defn delete-current-project []
  (when (b/current-project-id)
    (log/info "deleting current project")
    (go-project-route "/settings")
    (b/click (xpath "//button[contains(text(),'Project...')]"))
    (b/click (xpath "//button[text()='Confirm']"))
    (b/wait-until-exists x/create-project-text)))

(defn panel-name [panel-keys]
  (str/join "_" (map name panel-keys)))

(defn panel-exists? [panel & {:keys [wait?] :or {wait? true}}]
  (b/exists? {:css (str "div#" (panel-name panel))}
             :wait? wait?))
