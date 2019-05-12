(ns sysrev.test.browser.core
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [cljs.build.api :as cljs]
            [clj-webdriver.driver :as driver]
            [clj-webdriver.taxi :as taxi]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.api :as api]
            [sysrev.config.core :refer [env]]
            [sysrev.db.core :as db :refer [do-query do-execute with-transaction]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.db.groups :as groups]
            [sysrev.stripe :as stripe]
            [sysrev.test.core :as test]
            [sysrev.shared.util :as sutil :refer [parse-integer]])
  (:import [org.openqa.selenium.chrome ChromeOptions ChromeDriver]
           [org.openqa.selenium.remote DesiredCapabilities CapabilityType]
           [org.openqa.selenium.logging LoggingPreferences LogType]
           [org.openqa.selenium StaleElementReferenceException]
           [java.util.logging Level]))

(defonce active-webdriver (atom nil))

(defn start-webdriver [& [restart?]]
  (if (and @active-webdriver (not restart?))
    @active-webdriver
    (do (when @active-webdriver
          (try (taxi/quit) (catch Throwable e nil)))
        (reset! active-webdriver
                (let [opts (doto (ChromeOptions.)
                             (.addArguments ["window-size=1920,1080" "headless"]))
                      chromedriver (ChromeDriver.
                                    (doto (DesiredCapabilities. (DesiredCapabilities/chrome))
                                      (.setCapability ChromeOptions/CAPABILITY opts)))
                      driver (driver/init-driver {:webdriver chromedriver})]
                  (taxi/set-driver! driver))))))

(defn start-visual-webdriver
  "Starts a visible Chrome webdriver instance for taxi interaction.

  When using this, test functions should be run directly (not with `run-tests`).

  Can be closed with `stop-webdriver` when finished."
  [& [restart?]]
  (if (and @active-webdriver (not restart?))
    @active-webdriver
    (do (when @active-webdriver
          (try (taxi/quit) (catch Throwable e nil)))
        (reset! active-webdriver
                (let [opts (doto (ChromeOptions.)
                             (.addArguments
                              ["window-size=1200,800"]))
                      chromedriver (ChromeDriver.
                                    (doto (DesiredCapabilities. (DesiredCapabilities/chrome))
                                      (.setCapability ChromeOptions/CAPABILITY opts)))
                      driver (driver/init-driver {:webdriver chromedriver})]
                  (taxi/set-driver! driver))))))

(defn stop-webdriver []
  (when @active-webdriver
    (taxi/quit)
    (reset! active-webdriver nil)))

(defonce webdriver-shutdown-hook (atom nil))

(defn ensure-webdriver-shutdown-hook
  "Ensures that any chromedriver process is killed when JVM exits."
  []
  (when-not @webdriver-shutdown-hook
    (let [runtime (Runtime/getRuntime)]
      (.addShutdownHook runtime (Thread. #(stop-webdriver)))
      (reset! webdriver-shutdown-hook true))))

(def test-login
  {:email "browser+test@insilica.co"
   :password "1234567890"})

(defn delete-test-user [& {:keys [email] :or {email (:email test-login)}}]
  (with-transaction
    (when-let [{:keys [user-id stripe-id] :as user}
               (users/get-user-by-email email)]
      (when stripe-id
        (try (stripe/delete-customer! user)
             (catch Throwable t nil)))
      (when user-id
        (-> (delete-from :compensation-user-period)
            (where [:= :web-user-id user-id])
            do-execute))
      (users/delete-user-by-email email))))

(defn create-test-user [& {:keys [email password project-id]
                           :or {email (:email test-login)
                                password (:password test-login)
                                project-id nil}}]
  (with-transaction
    (delete-test-user :email email)
    (users/create-user email password :project-id project-id)))

(defn displayed-now?
  "Wrapper for taxi/displayed? to handle common exceptions. Returns true
  if an element matching q currently exists and is displayed, false if
  not."
  [q]
  (boolean (some #(boolean (try (and (taxi/exists? %) (taxi/displayed? %))
                                (catch Throwable e false)))
                 (taxi/elements q))))

(defn try-wait
  "Runs a wait function in try-catch block to avoid exception on
  timeout; returns true on success, false on timeout."
  [wait-fn & args]
  (try (apply wait-fn args) true
       (catch Throwable e
         (log/info "try-wait" wait-fn "timed out")
         false)))

(defn wait-until
  "Wrapper for taxi/wait-until with default values for timeout and
  interval. Waits until function pred evaluates as true, testing every
  interval ms until timeout ms have elapsed, or throws exception on
  timeout."
  [pred & [timeout interval]]
  (let [timeout (or timeout (if (test/remote-test?) 10000 5000))
        interval (or interval 25)]
    (when-not (pred)
      (Thread/sleep interval)
      (taxi/wait-until pred timeout interval))))

(defn wait-until-exists
  "Waits until an element matching q exists, or throws exception on
  timeout."
  [q & [timeout interval]]
  (wait-until #(taxi/exists? q) timeout interval))

(defn wait-until-displayed
  "Waits until an element matching q exists and is displayed, or throws
  exception on timeout."
  [q & [timeout interval]]
  (wait-until #(displayed-now? q) timeout interval))

(defn wait-until-loading-completes
  [& {:keys [timeout interval pre-wait] :or {pre-wait false}}]
  (let [timeout (if (test/remote-test?) 45000 timeout)]
    (when pre-wait (Thread/sleep (if (integer? pre-wait) pre-wait 75)))
    (is (try-wait wait-until #(every? (complement displayed-now?)
                                      ["div.ui.loader.active"
                                       "div.ui.dimmer.active > .ui.loader"
                                       ".ui.button.loading"])
                  timeout interval))))

(defn current-project-id
  "Reads project id from current url. Waits a short time before
  returning nil if no project id is immediately found, unless now is
  true."
  [& [now]]
  (letfn [(lookup-id []
            (let [[_ id-str] (re-matches #".*/p/(\d+)/?.*" (taxi/current-url))]
              (some-> id-str parse-integer)))]
    (if now
      (lookup-id)
      (when (try-wait wait-until #(integer? (lookup-id)) 2500 50)
        (lookup-id)))))

(defn current-project-route
  "Returns substring of current url after base url for project. Waits a
  short time before returning nil if no project id is immediately
  found, unless now is true."
  [& [now]]
  (when-let [project-id (current-project-id now)]
    (second (re-matches (re-pattern
                         (format ".*/p/%d(.*)$" project-id))
                        (taxi/current-url)))))

(defn set-input-text [q text & {:keys [delay clear?] :or {delay 20 clear? true}}]
  (wait-until-displayed q)
  (when clear? (taxi/clear q))
  (Thread/sleep delay)
  (taxi/input-text q text)
  (Thread/sleep delay))

(defn set-input-text-per-char
  [q text & {:keys [delay clear?] :or {delay 20 clear? true}}]
  (wait-until-displayed q)
  (when clear? (taxi/clear q))
  (Thread/sleep delay)
  (let [e (taxi/element q)]
    (doseq [c text]
      (taxi/input-text e (str c))
      (Thread/sleep 20)))
  (Thread/sleep delay))

(defn input-text [q text & {:keys [delay] :as opts}]
  (sutil/apply-keyargs set-input-text
                       q text (merge opts {:clear? false})))

(defn exists? [q & {:keys [wait? timeout interval] :or {wait? true}}]
  (when wait? (wait-until-exists q timeout interval))
  (let [result (taxi/exists? q)]
    (when wait? (wait-until-loading-completes :pre-wait true))
    result))

(defn is-xpath?
  "Test whether q is taxi query in xpath form."
  [q]
  (let [s (cond (string? q)                q
                (and (map? q) (:xpath q))  (:xpath q))]
    (boolean (and s (str/starts-with? s "//")))))

(defn not-class
  "If taxi query q is CSS form, add restriction against class c."
  [q c]
  (let [suffix (format ":not([class*=\"%s\"])" c)
        alter-css #(cond-> %
                     (not (str/includes? % suffix))
                     (str suffix))]
    (cond (is-xpath? q)            q
          (string? q)              (alter-css q)
          (and (map? q) (:css q))  (update q :css alter-css)
          :else                    q)))

(defn not-disabled
  "If taxi query q is CSS form, add restriction against \"disabled\" class."
  [q]
  (not-class q "disabled"))

(defn not-loading
  "If taxi query q is CSS form, add restriction against \"loading\" class."
  [q]
  (not-class q "loading"))

(defn click [q & {:keys [if-not-exists delay displayed?]
                  :or {if-not-exists :wait, delay 30, displayed? false}}]
  (let [q (not-disabled q) ; auto-exclude "disabled" class when q is css
        go (fn []
             (when (= if-not-exists :wait)
               (if displayed? (wait-until-displayed q) (wait-until-exists q)))
             (when-not (and (= if-not-exists :skip) (not (taxi/exists? q)))
               (taxi/click q)))]
    (Thread/sleep 30)
    (try (go)
         (catch Throwable e
           (wait-until-loading-completes :pre-wait (+ delay 50))
           (go)))
    (Thread/sleep delay)))

;; based on: https://crossclj.info/ns/io.aviso/taxi-toolkit/0.3.1/io.aviso.taxi-toolkit.ui.html#_clear-with-backspace
(defn backspace-clear
  "Hit backspace in input-element length times. Always returns true"
  [length input-element]
  (wait-until-displayed input-element)
  (dotimes [_ length]
    (taxi/send-keys input-element org.openqa.selenium.Keys/BACK_SPACE)
    (Thread/sleep 20))
  true)

(defn take-screenshot [& [error?]]
  (let [filename (str "/tmp/screenshot-" (System/currentTimeMillis) ".png")
        level (if error? :error :info)]
    (log/logp level "Saving screenshot:" filename)
    (try (taxi/take-screenshot :file filename)
         (catch Throwable e
           (log/error "Screenshot failed:" (type e) (.getMessage e))))))

(defmacro deftest-browser [name enable bindings body & {:keys [cleanup]}]
  (let [name-str (clojure.core/name name)]
    `(deftest ~name
       (when ~enable
         (let ~bindings
           (try (log/info "running" ~name-str)
                ~body
                (catch Throwable e#
                  (take-screenshot true)
                  (throw e#))
                (finally ~cleanup)))))))

(defn cleanup-browser-test-projects []
  (project/delete-all-projects-with-name "Sysrev Browser Test")
  (when-let [test-user-id (:user-id (users/get-user-by-email (:email test-login)))]
    (project/delete-solo-projects-from-user test-user-id)))

(defn current-frame-names []
  (->> (taxi/xpath-finder "//iframe")
       (map #(taxi/attribute % :name))))

(defn get-elements-text
  "Returns vector of taxi/text values for the elements matching q.
  Waits until at least one element is displayed unless wait? is
  logical false."
  [q & {:keys [wait?] :or {wait? true}}]
  (when wait? (wait-until-displayed q))
  (mapv taxi/text (taxi/elements q)))

;;;
;;; NOTE: Compensation entries should not be deleted like this except in testing.
;;;
(defn delete-compensation-by-id [project-id compensation-id]
  ;; delete from compensation-user-period
  (-> (delete-from :compensation-user-period)
      (where [:= :compensation-id compensation-id])
      do-execute)
  ;; delete from compensation-project-default
  (-> (delete-from :compensation-project-default)
      (where [:= :compensation-id compensation-id])
      do-execute)
  ;; delete from compensation-project
  (-> (delete-from :compensation-project)
      (where [:= :compensation-id compensation-id])
      do-execute)
  ;; delete from compensation
  (-> (delete-from :compensation)
      (where [:= :id compensation-id])
      do-execute))

(defn delete-project-compensations [project-id]
  (doseq [{:keys [compensation-id]} (-> (select :compensation-id)
                                        (from :compensation-project)
                                        (where [:= :project-id project-id])
                                        do-query)]
    (delete-compensation-by-id project-id compensation-id)))

(defn delete-test-user-projects! [user-id & [compensations]]
  (doseq [{:keys [project-id]} (users/user-projects user-id)]
    (when compensations (delete-project-compensations project-id))
    (project/delete-project project-id)))

(defn delete-test-user-groups! [user-id]
  (doseq [{:keys [id]} (groups/read-groups user-id)]
    (groups/delete-group! id)))

(defn cleanup-test-user!
  "Deletes a test user by user-id or email, along with other entities
  the user is associated with."
  [& {:keys [user-id email projects compensations groups]
      :or {projects true, compensations true, groups false}}]
  (assert (or (integer? user-id) (string? email)))
  (assert (not (and (integer? user-id) (string? email))))
  (let [email (or email (:email (users/get-user-by-id user-id)))
        user-id (or user-id (:user-id (users/get-user-by-email email)))]
    (when (and email user-id)
      (when projects (delete-test-user-projects! user-id compensations))
      (when groups (delete-test-user-groups! user-id))
      (delete-test-user :email email))))

(defn url->path
  "Returns relative path component of URL string."
  [uri]
  (.getPath (java.net.URI. uri)))

(defn path->url
  "Returns full URL from relative path string, based on test config."
  [path]
  (let [path (if (empty? path) "/" path)]
    (str (:url (test/get-selenium-config))
         (if (= (nth path 0) \/)
           (subs path 1) path))))

(defmacro is-soon
  "Runs (is pred-form) after attempting to wait for pred-form to
  evaluate as logical true."
  [pred-form & [timeout interval]]
  `(do (try-wait wait-until (fn [] ~pred-form) ~timeout ~interval)
       (is ~pred-form)))

(defn is-current-path
  "Runs test assertion that current URL matches relative path."
  [path]
  (is-soon (= path (url->path (taxi/current-url)))))

(defn init-route [path & {:keys [silent]}]
  (let [full-url (path->url path)]
    (when-not silent (log/info "loading" full-url))
    (taxi/to full-url)
    (wait-until-loading-completes :pre-wait 100)
    (wait-until-loading-completes :pre-wait 100)
    (taxi/execute-script "sysrev.base.toggle_analytics(false);")
    (let [fn-count (taxi/execute-script "return sysrev.core.spec_instrument();")]
      #_ (log/info "instrumented" fn-count "cljs functions")
      (assert (> fn-count 0) "no spec functions were instrumented")))
  nil)

(defn- ensure-logged-out []
  (try (when (taxi/exists? "a#log-out-link")
         (click "a#log-out-link" :if-not-exists :skip)
         (Thread/sleep 100))
       (catch Throwable _ nil)))

(defn webdriver-fixture-once [f]
  (f))

(defn webdriver-fixture-each [f]
  (let [local? (= "localhost" (:host (test/get-selenium-config)))
        cache? @db/query-cache-enabled]
    (do (when-not local? (reset! db/query-cache-enabled false))
        (when (test/db-connected?) (create-test-user))
        (ensure-webdriver-shutdown-hook) ;; register jvm shutdown hook
        #_ (start-webdriver) ;; use existing webdriver if running
        (start-webdriver true)
        #_ (try (ensure-logged-out) (init-route "/")
                ;; try restarting webdriver if unable to load page
                (catch Throwable _ (start-webdriver true) (init-route "/")))
        (f)
        #_ (ensure-logged-out) ;; log out to set up for next test
        (when-not local? (reset! db/query-cache-enabled cache?)))))
