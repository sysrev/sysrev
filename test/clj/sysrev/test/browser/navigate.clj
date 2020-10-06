(ns sysrev.test.browser.navigate
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b]
            [sysrev.test.browser.xpath :as x :refer [xpath]]))

(defn go-route [path & {:keys [wait-ms pre-wait-ms silent]
                        :or {wait-ms 50 pre-wait-ms 30}}]
  (let [current (taxi/current-url)
        path (if (empty? path) "/" path)]
    (cond (or (not (string? current))
              (not (str/includes? current (:url (test/get-selenium-config)))))
          (b/init-route path)
          (not= current (b/path->url path))
          (do (when-not silent
                (log/info "navigating to" path))
              (b/test-browser-console-clean :assert? true)
              (when pre-wait-ms
                (b/wait-until-loading-completes :pre-wait pre-wait-ms))
              (taxi/execute-script (format "sysrev.nav.set_token(\"%s\")" path))
              (b/wait-until-loading-completes :pre-wait (or (some-> wait-ms (quot 2))
                                                            25)
                                              :loop 2)
              (b/test-browser-console-clean :assert? true)))
    nil))

(defn go-project-route [suburi & {:keys [project-id wait-ms pre-wait-ms silent]
                                  :or {wait-ms 50 pre-wait-ms 30}}]
  (let [project-id (or project-id (b/current-project-id))
        current (b/url->path (taxi/current-url))
        ;; TODO: use server-side lookup to get project base url
        match-uri (second (re-matches #"(.*/p/[\d]+)(.*)" current))
        base-uri (or match-uri (str "/p/" project-id))
        review? (= suburi "/review")]
    (assert (integer? project-id))
    (go-route (str base-uri suburi)
              :wait-ms (cond review? 200 :else wait-ms)
              :pre-wait-ms (cond review? 200 :else pre-wait-ms)
              :silent silent)
    ;; this is a hack for the occasional times that /articles
    ;; doesn't load the first time it is clicked
    (when (and (= suburi "/articles")
               (not (taxi/exists? (xpath "//a[contains(text(),'Add/Manage Articles')]"))))
      (go-route (str base-uri "/manage") :wait-ms wait-ms :silent silent)
      (go-route (str base-uri "/articles") :wait-ms wait-ms :silent silent))))

(defn log-out [& {:keys [silent]}]
  (when (taxi/exists? "a#log-out-link")
    (when-not silent (log/info "logging out"))
    (b/wait-until-loading-completes :pre-wait true)
    (b/click "a#log-out-link" :if-not-exists :skip)
    (b/wait-until-loading-completes :pre-wait true)))

(defn log-in [email & [password]]
  (assert email)
  (let [password (or password b/test-password)]
    (log/info "logging in" (str "(" email ")"))
    (go-route "/" :silent true)
    (log-out :silent true)
    (go-route "/login" :silent true)
    (b/wait-until-displayed (xpath "//button[contains(text(),'Login')]"))
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (b/wait-until-loading-completes :pre-wait 40 :loop 2)
    (go-route "/" :silent true)
    (b/wait-until-loading-completes :pre-wait 40 :loop 2)
    #_ (log/info "login successful")))

(defn register-user [email & [password]]
  (assert email)
  (let [password (or password b/test-password)]
    (log/info "registering user"  (str "(" email ")"))
    (log-out :silent true)
    (go-route "/register" :silent true)
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (b/wait-until-exists "#new-project.button")
    (b/wait-until-loading-completes :pre-wait true :loop 2)
    #_ (log/info "register successful")))

(defn wait-until-overview-ready []
  (-> (b/not-disabled (x/project-menu-item :overview))
      (b/wait-until-exists 10000)))

(defn new-project [project-name]
  (log/info "creating project" (pr-str project-name))
  (go-route "/" :silent true)
  (b/click "#new-project.button")
  (b/set-input-text "#create-project div.project-name input" project-name)
  (b/click (xpath "//button[contains(text(),'Create Project')]"))
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath "//div[contains(@class,'project-title')]"
          "//a[contains(text(),'" project-name "')]"))
  (b/wait-until-loading-completes :pre-wait true))

(defn open-project [name]
  (log/info "opening project" (pr-str name))
  (go-route "/" :silent true)
  (b/click (x/project-title-value name))
  (b/wait-until-loading-completes :pre-wait true :loop 2))

(defn delete-current-project []
  (when (b/current-project-id nil 1000)
    (log/info "deleting current project")
    (go-project-route "/settings" :silent true)
    (b/click (xpath "//button[contains(text(),'Project...')]"))
    (b/click (xpath "//button[text()='Confirm']"))
    (b/wait-until-loading-completes :pre-wait true)))

(defn panel-name [panel-keys]
  (str/join "_" (map name panel-keys)))

(defn panel-exists? [panel & {:keys [wait?] :or {wait? true}}]
  (b/exists? (str "div#" (panel-name panel)) :wait? wait?))
