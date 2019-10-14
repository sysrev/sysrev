(ns sysrev.test.browser.navigate
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test :refer [completes?]]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.shared.util :as sutil :refer [in? parse-integer]]))

(defn go-route [path & {:keys [wait-ms pre-wait-ms silent]
                        :or {wait-ms 20}}]
  (let [current (taxi/current-url)
        path (if (empty? path) "/" path)]
    (cond (or (not (string? current))
              (not (str/includes? current (:url (test/get-selenium-config)))))
          (b/init-route path)
          (not= current (b/path->url path))
          (do (when-not silent
                (log/info "navigating to" path))
              (when pre-wait-ms
                (b/wait-until-loading-completes :pre-wait pre-wait-ms))
              (taxi/execute-script (format "sysrev.nav.set_token(\"%s\")" path))
              (b/wait-until-loading-completes :pre-wait wait-ms)))
    nil))

(defn go-project-route [suburi & {:keys [project-id wait-ms pre-wait-ms silent]}]
  (let [project-id (or project-id (b/current-project-id))
        current (b/url->path (taxi/current-url))
        ;; TODO: use server-side lookup to get project base url
        match-uri (second (re-matches #"(.*/p/[\d]+)(.*)" current))
        base-uri (or match-uri (str "/p/" project-id))]
    (assert (integer? project-id))
    (go-route (str base-uri suburi) :wait-ms wait-ms :silent silent
              :pre-wait-ms (or pre-wait-ms
                               (when (= suburi "/review") 25)))))

(defn log-out [& {:keys [silent]}]
  (when (taxi/exists? "a#log-out-link")
    (when-not silent (log/info "logging out"))
    (b/wait-until-loading-completes :pre-wait 10)
    (b/click "a#log-out-link" :if-not-exists :skip)
    (b/wait-until-loading-completes :pre-wait true)))

(defn log-in [& [email password]]
  (let [email (or email (:email b/test-login))
        password (or password (:password b/test-login))]
    (log/info "logging in" (str "(" email ")"))
    (go-route "/" :silent true)
    (log-out :silent true)
    (go-route "/login" :silent true)
    (b/wait-until-displayed (xpath "//button[contains(text(),'Login')]"))
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (b/wait-until-loading-completes :pre-wait true)
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
  (b/set-input-text "form.create-project div.project-name input" project-name)
  (b/click "form.create-project .button.create-project")
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath (format "//span[contains(@class,'project-title') and text()='%s']" project-name)
          "//ancestor::div[@id='project']"))
  (b/wait-until-loading-completes :pre-wait true)
  #_ (log/info "project created"))

(defn open-project [name]
  (log/info "opening project" (pr-str name))
  (go-route "/" :silent true)
  (b/click (x/project-title-value name) :delay 30))

(defn delete-current-project []
  (when (b/current-project-id nil 1000)
    (log/info "deleting current project")
    (go-project-route "/settings" :silent true :wait-ms 50)
    (b/click (xpath "//button[contains(text(),'Project...')]"))
    (b/click (xpath "//button[text()='Confirm']"))
    (b/wait-until-exists "form.create-project")
    (b/wait-until-loading-completes :pre-wait true)))

(defn panel-name [panel-keys]
  (str/join "_" (map name panel-keys)))

(defn panel-exists? [panel & {:keys [wait?] :or {wait? true}}]
  (b/exists? (str "div#" (panel-name panel)) :wait? wait?))
