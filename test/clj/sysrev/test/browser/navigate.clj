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
    (log/info "loading" full-url)
    (taxi/to full-url)
    (b/wait-until-loading-completes :pre-wait 100)
    (b/wait-until-loading-completes :pre-wait 100)
    (taxi/execute-script "sysrev.base.toggle_analytics(false);")
    (let [fn-count (taxi/execute-script "return sysrev.core.spec_instrument();")]
      #_ (log/info "instrumented" fn-count "cljs functions")
      (assert (> fn-count 0) "no spec functions were instrumented")))
  nil)

(defn go-route [& [path wait-ms]]
  (let [current (taxi/current-url)
        path (if (empty? path) "/" path)]
    (cond (or (not (string? current))
              (not (str/includes? current (:url (test/get-selenium-config)))))
          (init-route path)
          (not= current (path->full-url path))
          (do (b/wait-until-loading-completes :pre-wait 50)
              (log/info "navigating to" path)
              #_ (taxi/get-url (path->full-url path))
              (taxi/execute-script (format "sysrev.nav.set_token(\"%s\")" path))
              (b/wait-until-loading-completes :pre-wait (or wait-ms true))))
    nil))

(defn go-project-route [suburi & [project-id]]
  (let [project-id (or project-id (b/current-project-id))]
    (assert (integer? project-id))
    (go-route (str "/p/" project-id suburi))))

(defn log-out []
  (when (taxi/exists? "a#log-out-link")
    (log/info "logging out")
    (b/click "a#log-out-link" :if-not-exists :skip)
    (b/wait-until-exists #"login-register-panel")))

(defn log-in [& [email password]]
  (let [email (or email (:email b/test-login))
        password (or password (:password b/test-login))]
    (log/info "logging in" (str "(" email ")"))
    (go-route "/")
    (log-out)
    (go-route "/login")
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (Thread/sleep 100)
    (go-route "/")
    (log/info "login successful")))

(defn register-user [& [email password]]
  (let [email (or email (:email b/test-login))
        password (or password (:password b/test-login))]
    (log/info "registering user"  (str "(" email ")"))
    (log-out)
    (go-route "/register")
    (b/set-input-text "input[name='email']" email)
    (b/set-input-text "input[name='password']" password)
    (b/click "button[name='submit']")
    (b/wait-until-exists "form.create-project")
    (log/info "register successful")))

(defn wait-until-overview-ready []
  (-> (b/not-disabled (x/project-menu-item :overview))
      (b/wait-until-exists 10000)))

(defn new-project [project-name]
  (log/info "creating project" (pr-str project-name))
  (go-route "/")
  (b/wait-until-exists "form.create-project")
  (b/set-input-text "form.create-project div.project-name input" project-name)
  (b/click "form.create-project .button.create-project")
  (Thread/sleep 100)
  (when (test/remote-test?) (Thread/sleep 500))
  (b/wait-until-exists
   (xpath (format "//span[contains(@class,'project-title') and text()='%s']" project-name)
          "//ancestor::div[@id='project']"))
  (b/wait-until-loading-completes :pre-wait 100))

(defn open-project [name]
  (log/info "opening project" (pr-str name))
  (go-route "/")
  (b/click (x/project-title-value name)))

(defn delete-current-project []
  (when (b/current-project-id true)
    (log/info "deleting current project")
    (go-project-route "/settings")
    (b/click (xpath "//button[contains(text(),'Project...')]"))
    (b/click (xpath "//button[text()='Confirm']"))
    (b/wait-until-exists "form.create-project")))

(defn panel-name [panel-keys]
  (str/join "_" (map name panel-keys)))

(defn panel-exists? [panel & {:keys [wait?] :or {wait? true}}]
  (b/exists? (str "div#" (panel-name panel)) :wait? wait?))

(defn get-path
  "return the path of string uri"
  [uri]
  (-> uri java.net.URI. .getPath))

(defn current-path?
  "Is the browser currently at the relative-path?"
  [relative-path]
  (b/wait-until #(= (-> (taxi/current-url) get-path)
                    relative-path))
  (is (= (-> (taxi/current-url) get-path)
         relative-path)))
