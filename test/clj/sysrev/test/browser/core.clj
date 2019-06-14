(ns sysrev.test.browser.core
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
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
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [parse-integer ensure-pred]])
  (:import [org.openqa.selenium.chrome ChromeOptions ChromeDriver]
           [org.openqa.selenium.remote DesiredCapabilities CapabilityType]
           [org.openqa.selenium.logging LoggingPreferences LogType]
           [org.openqa.selenium StaleElementReferenceException]
           [java.util.logging Level]))

(defonce active-webdriver (atom nil))
(defonce active-webdriver-config (atom nil))

(defn browser-console-logs []
  (try (not-empty (taxi/execute-script "return sysrev.base.get_console_logs();"))
       (catch Throwable e
         (log/warn "unable to read console logs"))))

(defn browser-console-errors []
  (try (not-empty (taxi/execute-script "return sysrev.base.get_console_errors();"))
       (catch Throwable e
         (log/warn "unable to read console errors"))))

(defn log-console-messages [& [error?]]
  (let [level (if error? :error :info)]
    (if-let [logs (browser-console-logs)]
      (log/logf level "browser console logs:\n\n%s" logs)
      (log/logp level "browser console logs: (none)"))
    (if-let [errors (browser-console-errors)]
      (log/logf level "browser console errors:\n\n%s" errors)
      (log/logp level "browser console errors: (none)"))))

(defn start-webdriver [& [restart?]]
  (if (and @active-webdriver (not restart?))
    @active-webdriver
    (do (when @active-webdriver
          (try (taxi/quit) (catch Throwable e nil)))
        (reset! active-webdriver
                (let [opts (doto (ChromeOptions.)
                             (.addArguments ["window-size=1920,1080" "headless" "no-sandbox"]))
                      chromedriver (ChromeDriver.
                                    (doto (DesiredCapabilities. (DesiredCapabilities/chrome))
                                      (.setCapability ChromeOptions/CAPABILITY opts)))
                      driver (driver/init-driver {:webdriver chromedriver})]
                  (taxi/set-driver! driver)))
        (reset! active-webdriver-config {:visual false})
        @active-webdriver)))

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
                  (taxi/set-driver! driver)))
        (reset! active-webdriver-config {:visual true})
        @active-webdriver)))

(defn stop-webdriver []
  (when @active-webdriver
    (taxi/quit)
    (reset! active-webdriver nil)
    (reset! active-webdriver-config nil)))

(defn visual-webdriver? []
  (and @active-webdriver (true? (:visual @active-webdriver-config))))

(defn standard-webdriver? []
  (and @active-webdriver (not (visual-webdriver?))))

(defonce webdriver-shutdown-hook (atom nil))

(defn ensure-webdriver-shutdown-hook
  "Ensures that any chromedriver process is killed when JVM exits."
  []
  (when-not @webdriver-shutdown-hook
    (let [runtime (Runtime/getRuntime)]
      (.addShutdownHook runtime (Thread. #(stop-webdriver)))
      (reset! webdriver-shutdown-hook true))))

(defn take-screenshot [& [error?]]
  (if (standard-webdriver?)
    (let [path (util/tempfile-path (str "screenshot-" (System/currentTimeMillis) ".png"))
          level (if error? :error :info)]
      (try (taxi/take-screenshot :file path)
           (log/logp level "Screenshot saved:" path)
           (catch Throwable e
             (log/error "Screenshot failed:" (type e) (.getMessage e)))))
    (log/info "Skipping take-screenshot (visual webdriver)")))

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
            (where [:= :user-id user-id])
            do-execute))
      (users/delete-user-by-email email))))

(defn create-test-user [& {:keys [email password project-id]
                           :or {email (:email test-login)
                                password (:password test-login)
                                project-id nil}}]
  (with-transaction
    (delete-test-user :email email)
    (let [{:keys [user-id] :as user} (users/create-user email password :project-id project-id)]
      (users/change-user-setting user-id :ui-theme "Dark")
      user)))

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
        interval (or interval 20)]
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
    (second (re-matches (re-pattern (format ".*/p/%d(.*)$" project-id))
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
      (Thread/sleep 10)))
  (Thread/sleep delay))

(defn input-text [q text & {:keys [delay] :as opts}]
  (sutil/apply-keyargs set-input-text
                       q text (merge opts {:clear? false})))

(defn exists? [q & {:keys [wait? timeout interval] :or {wait? true}}]
  (when wait? (wait-until-exists q timeout interval))
  (let [result (taxi/exists? q)]
    (when wait? (wait-until-loading-completes :pre-wait 30))
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
  ;; auto-exclude "disabled" class when q is css
  (let [q (not-disabled q)]
    (wait-until-loading-completes :pre-wait delay)
    (when (= if-not-exists :wait)
      (if displayed? (wait-until-displayed q) (wait-until-exists q)))
    (when-not (and (= if-not-exists :skip) (not (taxi/exists? q)))
      (taxi/click q))
    (wait-until-loading-completes :pre-wait delay)))

;; based on: https://crossclj.info/ns/io.aviso/taxi-toolkit/0.3.1/io.aviso.taxi-toolkit.ui.html#_clear-with-backspace
(defn backspace-clear
  "Hit backspace in input-element length times. Always returns true"
  [length input-element]
  (wait-until-displayed input-element)
  (dotimes [_ length]
    (taxi/send-keys input-element org.openqa.selenium.Keys/BACK_SPACE)
    (Thread/sleep 10))
  true)

(defmacro deftest-browser [name enable bindings body & {:keys [cleanup]}]
  (let [name-str (clojure.core/name name)]
    `(deftest ~name
       (when ~enable
         (util/with-print-time-elapsed ~name-str
           (let ~bindings
             (try (log/info "running" ~name-str)
                  ~body
                  (catch Throwable e#
                    (log/error "current-url:" (try (taxi/current-url)
                                                   (catch Throwable e2# nil)))
                    (log-console-messages true)
                    (take-screenshot true)
                    (throw e#))
                  (finally
                    (let [failed# (and (instance? clojure.lang.IDeref *report-counters*)
                                       (map? @*report-counters*)
                                       (or (pos? (:fail @*report-counters*))
                                           (pos? (:error @*report-counters*))))]
                      (when (or (not-empty (browser-console-logs))
                                (not-empty (browser-console-errors)))
                        (log-console-messages failed#)))
                    ~cleanup))))))))

(defn cleanup-browser-test-projects []
  (project/delete-all-projects-with-name "Sysrev Browser Test")
  (when-let [test-user-id (:user-id (users/get-user-by-email (:email test-login)))]
    (project/delete-solo-projects-from-user test-user-id)))

(defn current-frame-names []
  (->> (taxi/xpath-finder "//iframe")
       (mapv #(when (taxi/exists? %) (taxi/attribute % :name)))
       (filterv identity)))

(defn get-elements-text
  "Returns vector of taxi/text values for the elements matching q.
  Waits until at least one element is displayed unless wait? is
  logical false."
  [q & {:keys [wait? timeout] :or {wait? true timeout 1000}}]
  (when wait? (try (wait-until #(taxi/exists? q) :timeout timeout)
                   (catch Throwable e nil)))
  (if (taxi/exists? q)
    (mapv taxi/text (taxi/elements q))
    []))

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
      (where [:= :compensation-id compensation-id])
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
  (doseq [{:keys [group-id]} (groups/read-groups user-id)]
    (groups/delete-group! group-id)))

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

(defn reuse-webdriver? []
  (->> (:sysrev-reuse-webdriver env)
       (ensure-pred (every-pred string? not-empty (partial not= "0")))))

(defn webdriver-fixture-each [f]
  (let [local? (= "localhost" (:host (test/get-selenium-config)))
        cache? @db/query-cache-enabled]
    (do (when-not local? (reset! db/query-cache-enabled false))
        (when (test/db-connected?) (create-test-user))
        (ensure-webdriver-shutdown-hook) ;; register jvm shutdown hook
        (if (reuse-webdriver?)
          (do (start-webdriver) ;; use existing webdriver if running
              (try (ensure-logged-out) (init-route "/")
                   ;; try restarting webdriver if unable to load page
                   (catch Throwable _ (start-webdriver true) (init-route "/"))))
          (start-webdriver true))
        (f)
        (when (reuse-webdriver?)
          ;; log out to set up for next test
          (ensure-logged-out))
        (when-not local? (reset! db/query-cache-enabled cache?)))))
