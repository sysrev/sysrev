(ns sysrev.test.browser.navigate
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test :refer [completes?]]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.shared.util :as sutil :refer [in? parse-integer]]))

(defn go-route [path & {:keys [wait-ms silent]}]
  (let [current (taxi/current-url)
        path (if (empty? path) "/" path)]
    (cond (or (not (string? current))
              (not (str/includes? current (:url (test/get-selenium-config)))))
          (b/init-route path)
          (not= current (b/path->url path))
          (do (b/wait-until-loading-completes :pre-wait 50)
              (when-not silent (log/info "navigating to" path))
              (taxi/execute-script (format "sysrev.nav.set_token(\"%s\")" path))
              (b/wait-until-loading-completes :pre-wait (or wait-ms true))))
    nil))

(defn go-project-route [suburi & {:keys [project-id wait-ms silent]}]
  (let [project-id (or project-id (b/current-project-id))
        current (b/url->path (taxi/current-url))
        ;; TODO: use server-side lookup to get project base url
        base-uri (or (second (re-matches #"(.*/p/[\d]+)(.*)" current))
                     (str "/p/" project-id))]
    (assert (integer? project-id))
    (go-route (str base-uri suburi) :wait-ms wait-ms :silent silent)))

(defn log-out [& {:keys [silent]}]
  (when (taxi/exists? "a#log-out-link")
    (when-not silent (log/info "logging out"))
    (b/click "a#log-out-link" :if-not-exists :skip)
    (Thread/sleep 100)))

(defn log-in [& [email password]]
  (let [email (or email (:email b/test-login))
        password (or password (:password b/test-login))]
    (log/info "logging in" (str "(" email ")"))
    (go-route "/" :silent true)
    (log-out :silent true)
    (go-route "/login" :silent true)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (Thread/sleep 100)
    (go-route "/" :silent true)
    #_ (log/info "login successful")))

(defn register-user [& [email password]]
  (let [email (or email (:email b/test-login))
        password (or password (:password b/test-login))]
    (log/info "registering user"  (str "(" email ")"))
    (log-out :silent true)
    (go-route "/register" :silent true)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (b/wait-until-exists "form.create-project")
    #_ (log/info "register successful")))

(defn wait-until-overview-ready []
  (-> (b/not-disabled (x/project-menu-item :overview))
      (b/wait-until-exists 10000)))

(defn new-project [project-name]
  (log/info "creating project" (pr-str project-name))
  (go-route "/" :silent true)
  (b/wait-until-exists "form.create-project")
  (b/set-input-text "form.create-project div.project-name input" project-name)
  (b/click "form.create-project .button.create-project")
  (Thread/sleep 100)
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath (format "//span[contains(@class,'project-title') and text()='%s']" project-name)
          "//ancestor::div[@id='project']"))
  #_ (log/info "project created")
  (b/wait-until-loading-completes :pre-wait 200))

(defn open-project [name]
  (log/info "opening project" (pr-str name))
  (go-route "/" :silent true)
  (b/click (x/project-title-value name)))

(defn delete-current-project []
  (when (b/current-project-id true)
    (log/info "deleting current project")
    (go-project-route "/settings" :silent true)
    (b/click (xpath "//button[contains(text(),'Project...')]"))
    (b/click (xpath "//button[text()='Confirm']"))
    (b/wait-until-exists "form.create-project")))

(defn panel-name [panel-keys]
  (str/join "_" (map name panel-keys)))

(defn panel-exists? [panel & {:keys [wait?] :or {wait? true}}]
  (b/exists? (str "div#" (panel-name panel)) :wait? wait?))
