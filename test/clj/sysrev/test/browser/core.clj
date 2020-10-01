(ns sysrev.test.browser.core
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is *report-counters*]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clj-webdriver.driver :as driver]
            [clj-webdriver.taxi :as taxi :refer [*driver*]]
            [clj-webdriver.core :refer [->actions move-to-element click-and-hold
                                        move-by-offset release perform]]
            [me.raynes.fs :as fs]
            [sysrev.config :refer [env]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.user.core :as user]
            [sysrev.project.core :as project]
            [sysrev.group.core :as group]
            [sysrev.payment.stripe :as stripe]
            [sysrev.payment.plans :as plans]
            [sysrev.stacktrace :refer [print-cause-trace-custom]]
            [sysrev.test.core :as test :refer [succeeds?]]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.util :as util :refer [parse-integer ellipsis-middle ignore-exceptions]])
  (:import (org.openqa.selenium.chrome ChromeOptions ChromeDriver)))

(defonce ^:dynamic *wd* (atom nil))
(defonce ^:dynamic *wd-config* (atom nil))

(defn sysrev-url? []
  (when-let [url (util/ignore-exceptions (taxi/current-url))]
    (->> ["localhost" "sysrev"] (some #(str/includes? url %)))))

(defn browser-console-logs []
  (when (sysrev-url?)
    (try (not-empty (taxi/execute-script "return sysrev.base.get_console_logs();"))
         (catch Throwable _
           (log/warn "unable to read console logs")))))

(defn browser-console-warnings []
  (when (sysrev-url?)
    (try (not-empty (taxi/execute-script "return sysrev.base.get_console_warnings();"))
         (catch Throwable _
           (log/warn "unable to read console warnings")))))

(defn browser-console-errors []
  (when (sysrev-url?)
    (try (not-empty (taxi/execute-script "return sysrev.base.get_console_errors();"))
         (catch Throwable _
           (log/warn "unable to read console errors")))))

(defn test-browser-console-clean [& {:keys [assert?]}]
  (let [errors (browser-console-errors)
        warnings (browser-console-warnings)]
    (is (and (empty? errors) (empty? warnings)))
    (when assert?
      (assert (empty? errors) "errors in browser console")
      (assert (empty? warnings) "warnings in browser console"))
    nil))

(defn log-console-messages [& [level]]
  (when *driver*
    (let [level (or level :info)]
      (if-let [logs (browser-console-logs)]
        (log/logf level "browser console logs:\n\n%s" logs)
        (log/logp level "browser console logs: (none)"))
      (if-let [warnings (browser-console-warnings)]
        (log/logf level "browser console warnings:\n\n%s" warnings)
        (log/logp level "browser console warnings: (none)"))
      (if-let [errors (browser-console-errors)]
        (log/logf level "browser console errors:\n\n%s" errors)
        (log/logp level "browser console errors: (none)")))))

(defn current-windows []
  (let [active (taxi/window)]
    (mapv #(assoc % :active (= (:handle %) (:handle active)))
          (taxi/windows))))

(defmacro log-current-windows []
  `(if-let [windows# (seq (current-windows))]
     (do (log/info "current windows:")
         (doseq [w# windows#]
           (log/info (str (if (:active w#) "[*]" "[ ]") " "
                          "(" (ellipsis-middle (:url w#) 50 "...") ") "
                          "\"" (ellipsis-middle (:title w#) 40 "...") "\""))))
     (log/info "current windows: (none found)")))

(defn visual-webdriver? []
  (and @*wd* (true? (:visual @*wd-config*))))

(defn standard-webdriver? []
  (and @*wd* (not (visual-webdriver?))))

(def browser-test-window-size {:width 1600 :height 1000})

(defn ensure-webdriver-size []
  (when-not (visual-webdriver?)
    (try (let [test-width (:width browser-test-window-size)
               {:keys [width]} (taxi/window-size)]
           (when (< width test-width)
             (log/info "resizing chromedriver window")
             (taxi/window-resize browser-test-window-size)
             (let [{:keys [width]} (taxi/window-size)]
               (when (< width test-width)
                 (log/info "maximizing chromedriver window")
                 (taxi/window-maximize)
                 (let [{:keys [width]} (taxi/window-size)]
                   (when (< width test-width)
                     (log/warnf "window size still too small; width=%d" width)))))))
         (catch Throwable _
           (log/warn "exception in ensure-webdriver-size")))))

(defn start-webdriver [& [restart?]]
  (if (and @*wd* (not restart?))
    @*wd*
    (do (when @*wd* (-> (taxi/quit @*wd*) (ignore-exceptions)))
        (reset! *wd* (->> (doto (ChromeOptions.)
                            (.addArguments
                             (seq ["headless"
                                   "no-sandbox"
                                   #_ "disable-gpu"
                                   #_ "disable-dev-shm-usage"
                                   #_ "log-level=INFO"
                                   #_ "readable-timestamp"
                                   (format "window-size=%d,%d"
                                           (:width browser-test-window-size)
                                           (:height browser-test-window-size))])))
                          (ChromeDriver.)
                          (assoc {} :webdriver)
                          (driver/init-driver)
                          (taxi/set-driver!)))
        (reset! *wd-config* {:visual false})
        (ensure-webdriver-size)
        @*wd*)))

(defn start-visual-webdriver
  "Starts a visible Chrome webdriver instance for taxi interaction.

  When using this, test functions should be run directly (not with `run-tests`).

  Can be closed with `stop-webdriver` when finished."
  [& [restart?]]
  (if (and @*wd* (not restart?))
    @*wd*
    (do (when @*wd* (-> (taxi/quit @*wd*) (ignore-exceptions)))
        (reset! *wd* (->> (doto (ChromeOptions.)
                            (.addArguments [(format "window-size=%d,%d"
                                                    (:width browser-test-window-size)
                                                    (:height browser-test-window-size))]))
                          (ChromeDriver.)
                          (assoc {} :webdriver)
                          (driver/init-driver)
                          (taxi/set-driver!)))
        (reset! *wd-config* {:visual true})
        (ensure-webdriver-size)
        @*wd*)))

(defn stop-webdriver []
  (when @*wd*
    (taxi/quit @*wd*)
    (reset! *wd* nil)
    (reset! *wd-config* nil)))

(defn take-screenshot [& [level]]
  (if (standard-webdriver?)
    (let [path (util/tempfile-path (str "screenshot-" (System/currentTimeMillis) ".png"))
          level (or level :info)]
      (try (taxi/take-screenshot :file path)
           (log/logp level "Screenshot saved:" path)
           (catch Throwable e
             (log/error "Screenshot failed:" (type e) (.getMessage e)))))
    (log/info "Skipping take-screenshot (visual webdriver)")))

(def test-password "1234567890")

(defn delete-test-user [& {:keys [email user-id]}]
  (util/assert-single email user-id)
  (db/with-transaction
    (when-let [{:keys [user-id stripe-id]
                :as user} (if email
                            (user/user-by-email email)
                            (q/find-one :web-user {:user-id user-id}))]
      (when stripe-id
        (-> (stripe/delete-customer! user) (ignore-exceptions)))
      (when user-id
        (q/delete :compensation-user-period {:user-id user-id}))
      (if email
        (user/delete-user-by-email email)
        (user/delete-user user-id)))))

(defn create-test-user [& {:keys [email password project-id literal]
                           :or {email "browser+test@insilica.co"
                                password test-password
                                project-id nil}}]
  (let [[name domain] (str/split email #"@")
        email (if literal email
                  (format "%s+%s@%s" name (util/random-id) domain))]
    (db/with-transaction
      (delete-test-user :email email)
      (let [{:keys [user-id] :as user} (user/create-user email password :project-id project-id)]
        (user/change-user-setting user-id :ui-theme "Dark")
        (merge user {:password password})))))

(defn displayed-now?
  "Wrapper for taxi/displayed? to handle common exceptions. Returns true
  if an element matching q currently exists and is displayed, false if
  not."
  [q]
  (boolean (some #(succeeds? (and (taxi/exists? %) (taxi/displayed? %)))
                 (taxi/elements q))))

(defn try-wait
  "Runs a wait function in try-catch block to avoid exception on
  timeout; returns true on success, false on timeout."
  [wait-fn & args]
  (succeeds? (do (apply wait-fn args) true)))

(def web-default-interval
  (or (some-> (:sr-interval env) util/parse-integer)
      20))

(defn wait-until
  "Wrapper for taxi/wait-until with default values for timeout and
  interval. Waits until function pred evaluates as true, testing every
  interval ms until timeout ms have elapsed, or throws exception on
  timeout."
  [pred & [timeout interval]]
  (let [timeout (or timeout (if (test/remote-test?) 12500 10000))
        interval (or interval web-default-interval)]
    (when-not (pred)
      (Thread/sleep interval)
      (taxi/wait-until (fn [& _] (pred)) timeout interval))))

(defmacro is-soon
  "Runs (is pred-form) after attempting to wait for pred-form to
  evaluate as logical true."
  [pred-form & [timeout interval]]
  `(do (or (try-wait wait-until (fn [] ~pred-form) ~timeout ~interval)
           (take-screenshot :error))
       (is ~pred-form)))

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

(defn displayed?
  [q & [timeout interval]]
  (is-soon (displayed-now? q) timeout interval))

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

(defn ajax-activity-duration
  "Query browser for duration in milliseconds that ajax requests have
  been active (positive) or inactive (negative)."
  []
  (taxi/execute-script "return sysrev.loading.ajax_status();"))

(defn ajax-inactive?
  "Returns true if no ajax requests in browser have been active for
  duration milliseconds (default 30)."
  [& [duration]]
  (< (ajax-activity-duration) (- (or duration 30))))

(defn wait-until-loading-completes
  [& {:keys [timeout interval pre-wait loop inactive-ms] :or {pre-wait false}}]
  (dotimes [_ (or loop 1)]
    (when pre-wait (Thread/sleep (if (integer? pre-wait) pre-wait 25)))
    (wait-until #(and (ajax-inactive? inactive-ms)
                      (every? (complement taxi/exists?)
                              [(not-class "div.ui.loader.active"
                                          "loading-indicator")
                               "div.ui.dimmer.active > .ui.loader"
                               ".ui.button.loading"]))
                timeout interval)))

(defn current-project-id
  "Reads project id from current url. Waits a short time before
  returning nil if no project id is immediately found, unless now is
  true."
  [& [now wait-ms]]
  (letfn [(lookup-id []
            (let [[_ id-str] (re-matches #".*/p/(\d+)/?.*" (taxi/current-url))]
              (some-> id-str parse-integer)))]
    (if now
      (lookup-id)
      (when (try-wait wait-until #(integer? (lookup-id)) (or wait-ms 3000))
        (lookup-id)))))

(defn current-project-route
  "Returns substring of current url after base url for project. Waits a
  short time before returning nil if no project id is immediately
  found, unless now is true."
  [& [now]]
  (when-let [project-id (current-project-id now)]
    (second (re-matches (re-pattern (format ".*/p/%d(.*)$" project-id))
                        (taxi/current-url)))))

(defn set-input-text [q text & {:keys [delay clear?] :or {delay 40 clear? true}}]
  (let [q (not-disabled q)]
    (wait-until-displayed q)
    (when clear? (taxi/clear q))
    (Thread/sleep (quot delay 2))
    (taxi/input-text q text)
    (Thread/sleep (quot delay 2))))

(defn set-input-text-per-char [q text & {:keys [delay char-delay clear?]
                                         :or {delay 40 char-delay 30 clear? true}}]
  (let [q (not-disabled q)]
    (wait-until-displayed q)
    (when clear? (taxi/clear q))
    (Thread/sleep (quot delay 2))
    (let [e (taxi/element q)]
      (doseq [c text]
        (taxi/input-text e (str c))
        (Thread/sleep char-delay)))
    (Thread/sleep (quot delay 2))))

(defn exists? [q & {:keys [wait? timeout interval] :or {wait? true}}]
  (when wait?
    (try (wait-until-exists q timeout interval)
         (catch Throwable e
           (log/warnf "%s not found" (pr-str q))
           (take-screenshot :warn)
           (throw e))))
  (let [result (taxi/exists? q)]
    (when wait? (wait-until-loading-completes))
    result))

(defn text [q]
  (wait-until-displayed q)
  (-> q taxi/element taxi/text))

(defn text-is? [q value]
  (is-soon (and (displayed-now? q)
                (= (text q) value))))

(defn click [q & {:keys [if-not-exists delay displayed? external? timeout]
                  :or {if-not-exists :wait, delay 40}}]
  (letfn [(wait [ms]
            (if external?
              (Thread/sleep (+ ms 25))
              (wait-until-loading-completes :pre-wait ms :timeout timeout)))]
    ;; auto-exclude "disabled" class when q is css
    (let [q (if external? q
                (-> q (not-disabled) (not-loading)))]
      (when (= if-not-exists :wait)
        (if displayed?
          (is-soon (displayed-now? q))
          (is-soon (taxi/exists? q))))
      (when-not (and (= if-not-exists :skip) (not (taxi/exists? q)))
        (try (taxi/click q)
             (catch Throwable _
               (log/warnf "got exception clicking %s, trying again..." (pr-str q))
               (wait (+ delay 200))
               (taxi/click q))))
      (wait delay)
      (test-browser-console-clean :assert? true))))

;; based on: https://crossclj.info/ns/io.aviso/taxi-toolkit/0.3.1/io.aviso.taxi-toolkit.ui.html#_clear-with-backspace
(defn backspace-clear
  "Hit backspace in input-element length times. Always returns true"
  [length input-element]
  (wait-until-displayed input-element)
  (dotimes [_ length]
    (taxi/send-keys input-element org.openqa.selenium.Keys/BACK_SPACE)
    (Thread/sleep 20)))

(defmacro with-webdriver [& body]
  `(let [visual# (:visual @*wd-config*) ]
     (when visual# (stop-webdriver))
     (binding [*wd* (atom nil)
               *wd-config* (atom nil)
               taxi/*driver* nil]
       (if visual#
         (start-visual-webdriver true)
         (start-webdriver true))
       (try ~@body (finally (when-not visual# (stop-webdriver)))))))

(defmacro deftest-browser [name enable test-user bindings body & {:keys [cleanup]}]
  (let [name-str (clojure.core/name name)
        repl? (= :dev (:profile env))]
    `(deftest ~name
       (when (or ~repl? ~enable)
         (util/with-print-time-elapsed ~name-str
           (log/infof "")
           (log/infof "[[ %s started ]]" ~name-str)
           (with-webdriver
             (init-route "/" :silent true)
             (let [~test-user (if (and (test/db-connected?) @db/active-db)
                                (create-test-user)
                                {:email "browser+test@insilica.co"
                                 :password test-password})
                   ~@bindings]
               (try (when ~repl?
                      (try ~cleanup
                           (create-test-user :email (:email ~test-user) :literal true)
                           (catch Throwable e#
                             (log/warn "got exception in repl cleanup:" (str e#)))))
                    ~body
                    (test-browser-console-clean)
                    (catch Throwable e#
                      (log/error "current-url:" (-> (taxi/current-url) (ignore-exceptions)))
                      (test-browser-console-clean)
                      (log-console-messages :error)
                      (take-screenshot :error)
                      (throw e#))
                    (finally
                      (try (wait-until-loading-completes :pre-wait 50 :timeout 1500)
                           (catch Throwable e2#
                             (log/info "test cleanup - wait-until-loading-completes timed out")))
                      (let [failed# (and (instance? clojure.lang.IDeref *report-counters*)
                                         (map? @*report-counters*)
                                         (or (pos? (:fail @*report-counters*))
                                             (pos? (:error @*report-counters*))))]
                        (when (or (not-empty (browser-console-logs))
                                  (not-empty (browser-console-errors)))
                          (log-console-messages (if failed# :error :info))))
                      (when-not ~repl?
                        (try ~cleanup
                             (catch Throwable e#
                               (log/warnf "exception in test cleanup:\n%s"
                                          (with-out-str (print-cause-trace-custom e#)))))
                        #_ (ensure-logged-out)
                        (when (test/db-connected?)
                          (cleanup-test-user! :email (:email ~test-user)))))))))))))

(defn current-frame-names []
  (->> (taxi/xpath-finder "//iframe")
       (mapv #(when (taxi/exists? %) (taxi/attribute % :name)))
       (filterv identity)))

(defn get-elements-text
  "Returns vector of taxi/text values for the elements matching q.
  Waits until at least one element is displayed unless wait? is
  logical false."
  [q & {:keys [wait? timeout] :or {wait? true timeout 2000}}]
  (when wait?
    (ignore-exceptions (wait-until #(taxi/exists? q) :timeout timeout)))
  (if (taxi/exists? q)
    (mapv taxi/text (taxi/elements q))
    []))

(defn delete-test-user-projects! [user-id & [compensations]]
  (doseq [{:keys [project-id]} (user/user-projects user-id)]
    (when compensations (project/delete-project-compensations project-id))
    (project/delete-project project-id)))

(defn delete-test-user-groups! [user-id]
  (doseq [{:keys [group-id]} (group/read-groups user-id)]
    (group/delete-group! group-id)))

(defn cleanup-test-user!
  "Deletes a test user by user-id or email, along with other entities the user is associated with."
  [& {:keys [user-id email projects compensations groups]
      :or {projects true, compensations true, groups false}}]
  (util/assert-single user-id email)
  (let [email (or email (q/get-user user-id :email))
        user-id (or user-id (user/user-by-email email :user-id))]
    (when (and email user-id)
      (when projects (delete-test-user-projects! user-id compensations))
      (when groups (delete-test-user-groups! user-id))
      (when-let [stripe-id (q/get-user user-id :stripe-id)]
        (when-let [{:keys [sub-id]} (plans/user-current-plan user-id)]
          (stripe/delete-subscription! sub-id))
        (user/delete-user-stripe-customer! {:stripe-id stripe-id :user-id user-id}))
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

(defn is-current-path
  "Runs test assertion that current URL matches relative path."
  [path]
  (is-soon (= path (url->path (taxi/current-url)))))

(defn init-route [path & {:keys [silent]}]
  (let [full-url (path->url path)]
    (test-browser-console-clean)
    (when-not silent (log/info "loading" full-url))
    (taxi/to full-url)
    (wait-until-loading-completes :pre-wait true :loop 2)
    (taxi/execute-script "sysrev.base.toggle_analytics(false);")
    (let [fn-count (taxi/execute-script "return sysrev.core.spec_instrument();")]
      (if (pos-int? fn-count)
        nil #_ (log/info "instrumented" fn-count "cljs functions")
        (log/error "no cljs functions were instrumented"))))
  nil)

;; if this doesn't do anything, why not take it out? - James
(defn webdriver-fixture-once [f]
  (f))

(defn webdriver-fixture-each [f]
  (let [local? (= "localhost" (:host (test/get-selenium-config)))
        ;; cache? @db/query-cache-enabled
        ]
    (when-not local? (reset! db/query-cache-enabled false))
    #_ (when (test/db-connected?) (create-test-user))
    #_ (ensure-webdriver-shutdown-hook) ;; register jvm shutdown hook
    #_ (if (reuse-webdriver?)
         (do (start-webdriver) ;; use existing webdriver if running
             (ensure-webdriver-size)
             (try (ensure-logged-out) (init-route "/")
                  ;; try restarting webdriver if unable to load page
                  (catch Throwable _
                    (log/warn "restarting webdriver due to exception")
                    (start-webdriver true) (init-route "/"))))
         (start-webdriver true))
    (f)
    #_ (when (reuse-webdriver?)
         ;; log out to set up for next test
         (ensure-logged-out))
    #_ (when-not local? (reset! db/query-cache-enabled cache?))))

(defonce ^:private chromedriver-version-atom (atom nil))

(defn chromedriver-version []
  (or @chromedriver-version-atom
      (reset! chromedriver-version-atom
              (or (->> (util/shell "chromedriver" "--version")
                       :out
                       str/split-lines
                       (map #(second (re-matches #"^ChromeDriver (\d+)\.\d+.*$" %)))
                       (remove nil?)
                       first
                       parse-integer)
                  0))))

(defn click-drag-element [q & {:keys [start-x offset-x start-y offset-y delay]
                               :or {start-x 0 offset-x 0 start-y 0 offset-y 0
                                    delay 40}}]
  (let [start-x (or start-x 0)
        offset-x (or offset-x 0)
        start-y (or start-y 0)
        offset-y (or offset-y 0)
        ;; ChromeDriver 75 changes default behavior to use element
        ;; center as base position instead of top-left.
        center? (>= (chromedriver-version) 75)
        ;; Need to subtract half of element size in this case to get
        ;; the same positions as ChromeDriver <= 74.
        {:keys [width height]} (when center? (taxi/element-size q))
        adjust-x (when center? (- (quot width 2)))
        adjust-y (when center? (- (quot height 2)))
        x (if center?
            (-> (+ adjust-x start-x) (max adjust-x) (min (- adjust-x)))
            start-x)
        y (if center?
            (-> (+ adjust-y start-y) (max adjust-y) (min (- adjust-y)))
            start-y)]
    #_ (log/info (pr-str {:width width :height height
                          :start-x start-x :offset-x offset-x
                          :start-y start-y :offset-y offset-y
                          :x x :y y}))
    (Thread/sleep delay)
    #_{:clj-kondo/ignore [:invalid-arity]}
    (->actions *driver*
               (move-to-element (taxi/element q) x y)
               (click-and-hold) (move-by-offset offset-x offset-y) (release) (perform))
    (Thread/sleep delay)))

(defn check-for-error-message [error-message]
  (exists?
   (xpath
    "//div[contains(@class,'negative') and contains(@class,'message') and contains(text(),\""
    error-message "\")]")))

;; http://blog.fermium.io/how-to-send-files-to-a-dropzone-js-element-in-selenium/
(defn dropzone-upload
  "Given a filename, upload it to dropzone"
  [filename]
  (let [base64-file (-> filename io/resource io/file util/slurp-bytes util/bytes->base64)
        upload-blob-js (when (seq base64-file)
                         (str "function base64toBlob(r,e,n){e=e||\"\",n=n||512;for(var t=atob(r),a=[],o=0;o<t.length;o+=n){for(var l=t.slice(o,o+n),h=new Array(l.length),b=0;b<l.length;b++)h[b]=l.charCodeAt(b);var v=new Uint8Array(h);a.push(v)}var c=new Blob(a,{type:e}); c.name='" (fs/base-name filename) "'; return c} "
                              (format "return sysrev.util.add_dropzone_file_blob(base64toBlob, '%s');" base64-file)))]
    (taxi/execute-script upload-blob-js)))
