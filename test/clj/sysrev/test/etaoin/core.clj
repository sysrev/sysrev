(ns sysrev.test.etaoin.core
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.logging :as log]
            [cheshire.core :refer [generate-stream]]
            [etaoin.api :as ea]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b]
            [sysrev.util :as util])
  (:import [clojure.lang ExceptionInfo]
           java.util.Date
           [java.net URLDecoder]))

(defonce ^:dynamic *driver* (atom {}))
(defonce ^:dynamic *cleanup-users* (atom {}))

(defn root-url []
  (let [{:keys [url]} (test/get-selenium-config)]
    (subs url 0 (dec (count url)))))

(defn setup-visual-chromedriver!
  "Used to setup for testing at repl"
  []
  (reset! *driver* (ea/chrome)))

(defn js-execute [script]
  (ea/js-execute @*driver* script))

(defn get-url []
  (ea/get-url @*driver*))

(defn sysrev-url? []
  (when-let [url (util/ignore-exceptions (get-url))]
    (some #(str/includes? url %) #{"localhost" "sysrev"})))

(defn browser-console-logs []
  (when (sysrev-url?)
    (try (not-empty (js-execute "return sysrev.base.get_console_logs();"))
         (catch Throwable _
           (log/warn "unable to read console logs")))))

(defn browser-console-warnings []
  (when (sysrev-url?)
    (try (not-empty (js-execute "return sysrev.base.get_console_warnings();"))
         (catch Throwable _
           (log/warn "unable to read console warnings")))))

(defn browser-console-errors []
  (when (sysrev-url?)
    (try (not-empty (js-execute "return sysrev.base.get_console_errors();"))
         (catch Throwable _
           (log/warn "unable to read console errors")))))

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

(defn check-browser-console-clean []
  (when-not (and (empty? (browser-console-errors))
                 (empty? (browser-console-warnings)))
    (is (empty? (browser-console-errors)) "errors in browser console" )
    (is (empty? (browser-console-warnings)) "warnings in browser console")
    (log-console-messages :warn))
  nil)

(defn wait-exists [q & [timeout interval]]
  (ea/with-wait-timeout (or (some-> timeout (/ 1000.0)) 15)
    (ea/with-wait-interval (or (some-> interval (/ 1000.0)) 0.020)
      (ea/wait-exists @*driver* q))))

(defn exists? [q & {:keys [wait] :or {wait true}}]
  (when wait (wait-exists q))
  (ea/exists? @*driver* q))

(defn ajax-pending-requests []
  (some-> (js-execute "return sysrev.loading.all_pending_requests();")
          (util/read-transit-str)))

(defn ajax-activity-duration
  "Query browser for duration in milliseconds that ajax requests have
  been active (positive) or inactive (negative)."
  []
  (js-execute "return sysrev.loading.ajax_status();"))

(defn ajax-inactive?
  "Returns true if no ajax requests in browser have been active for
  duration milliseconds (default 30)."
  [& [duration]]
  (< (ajax-activity-duration) (- (b/make-delay (or duration 30)))))

(defn wait-loading
  [& {:keys [timeout interval pre-wait loop inactive-ms] :or {pre-wait false}}]
  (let [timeout (or timeout 15000)
        interval (or interval b/web-default-interval)]
    (dotimes [_ (or loop 1)]
      (when pre-wait (Thread/sleep (b/make-delay
                                    (if (integer? pre-wait) pre-wait 25))))
      (try (test/wait-until
            (fn [] (and (ajax-inactive? inactive-ms)
                        (every? #(not (ea/exists? @*driver* {:css %}))
                                b/loader-elements-css)))
            timeout interval)
           (catch ExceptionInfo e
             (when-not (ajax-inactive? inactive-ms)
               (log/warnf "[wait-loading] ajax blocking =>\n%s"
                          (try (-> (ajax-pending-requests) util/pp-str str/trim-newline)
                               (catch Throwable _
                                 "<error while trying to read pending requests>"))))
             (when-let [q-blocking (seq (filterv #(ea/exists? @*driver* {:css %})
                                                 b/loader-elements-css))]
               (log/warnf "[wait-loading] elements blocking =>\n%s"
                          (str/join "\n" q-blocking)))
             (throw e))))))

(defn go [relative-url & {:keys [init silent]
                          :or {init false silent false}}]
  (let [full-url (str (root-url) relative-url)]
    (when-not silent
      (if init
        (log/info "loading" full-url)
        (log/info "navigating to" relative-url)))
    (when-not init (wait-loading :pre-wait true))
    (when-not init (check-browser-console-clean))
    (if init
      (ea/go @*driver* full-url)
      (js-execute (format "sysrev.nav.set_token(\"%s\")" relative-url)))
    (wait-exists :app)
    (wait-loading :pre-wait true)
    (check-browser-console-clean)))

(defn click [q & {:keys [if-not-exists delay timeout external?]
                  :or {if-not-exists :wait, delay 50}}]
  (letfn [(wait [ms]
            (if external?
              (Thread/sleep (+ ms 25))
              (wait-loading :pre-wait ms :timeout timeout)))]
    (let [ ;; auto-exclude "disabled" class when q is css
          q (if external? q (-> q b/not-disabled b/not-loading))
          delay (b/make-delay delay)]
      (when (= if-not-exists :wait)
        (if timeout
          ;; wrap `ea/with-wait-timeout` only if timeout value was passed
          (ea/with-wait-timeout (/ timeout 1000.0)
            (ea/wait-enabled @*driver* q))
          ;; otherwise use global default
          (ea/wait-enabled @*driver* q)))
      (when-not (and (= if-not-exists :skip) (not (ea/exists? @*driver* q)))
        (try (ea/click @*driver* q)
             (catch Throwable _
               (log/warnf "got exception clicking %s, trying again..." (pr-str q))
               (wait (+ delay 200))
               (ea/click @*driver* q))))
      (wait delay)
      (check-browser-console-clean))))

(defn fill [q string & {:keys [delay clear?]
                        :or {delay 40, clear? false}}]
  (wait-loading :pre-wait delay)
  (wait-exists q)
  (when clear?
    (ea/clear @*driver* q)
    (wait-loading :pre-wait delay))
  (ea/fill @*driver* q string)
  (wait-loading :pre-wait delay))

(defn enabled? [q & {:keys [wait] :or {wait true}}]
  (when wait (wait-exists q))
  (ea/enabled? @*driver* q))

;; used for local debug purposes
(defn take-screenshot []
  (ea/screenshot @*driver* (str "./" (gensym) ".png")))

(defn- dump-logs [logs filename & [opt]]
  (generate-stream
   logs
   (io/writer filename)
   (merge {:pretty true} opt)))

(defn postmortem-handler
  "Based on etaoin postmortem handler, but using a different logging level"
  [{:keys [dir dir-src dir-img dir-log date-format]}]
  (let [dir     (or dir (ea/get-pwd))
        dir-img (or dir-img dir)
        dir-src (or dir-src dir)
        dir-log (or dir-log dir)

        file-tpl "%s-%s-%s-%s.%s"

        date-format (or date-format "yyyy-MM-dd-HH-mm-ss")
        params      [(-> @*driver* :type name)
                     (-> @*driver* :host)
                     (-> @*driver* :port)
                     (ea/format-date (Date.) date-format)]

        file-img (apply format file-tpl (conj params "png"))
        file-src (apply format file-tpl (conj params "html"))
        file-log (apply format file-tpl (conj params "json"))

        path-img (ea/join-path dir-img file-img)
        path-src (ea/join-path dir-src file-src)
        path-log (ea/join-path dir-log file-log)]

    (clojure.java.io/make-parents path-img)
    (clojure.java.io/make-parents path-src)
    (clojure.java.io/make-parents path-log)

    (when-not (and (empty? (browser-console-errors))
                   (empty? (browser-console-warnings)))
      (log-console-messages :error))

    (log/errorf "Writing screenshot: %s" path-img)
    (ea/screenshot @*driver* path-img)

    (log/errorf "Writing HTML source: %s" path-src)
    (spit path-src (ea/get-source @*driver*))

    (when (ea/supports-logs? @*driver*)
      (log/errorf "Writing console logs: %s" path-log)
      (dump-logs (ea/get-logs @*driver*) path-log))))

(defmacro with-postmortem
  "Copied from etaoin, but we are using a custom postmortem-handler"
  [opt & body]
  `(try ~@body
        (catch Exception e#
          (postmortem-handler ~opt)
          (throw e#))))

(defmacro deftest-etaoin
  "A macro for creating an etaoin browser test. Used in tandem with etaoin-fixture. A dynamic atom var of type vector, *cleanup-users*, is used to cleanup tests users. Populate it within the body of deftest-etaoin. e.g. `(swap! *cleanup-users* conj {:user-id user-id)`"
  [name body]
  (let [name-str (clojure.core/name name)]
    `(deftest ~name
       (binding [*cleanup-users* (atom [])]
         (util/with-print-time-elapsed ~name-str
           (log/infof "")
           (log/infof "[[ %s started ]]" ~name-str)
           (try (with-postmortem {:dir "/tmp/sysrev/etaoin"}
                  (ea/with-wait-timeout 15
                    (go "/" :init true)
                    ~body
                    (check-browser-console-clean)))
                (finally
                  (doseq [{user-id# :user-id} @*cleanup-users*]
                    (b/cleanup-test-user! :user-id user-id# :groups true)))))))))

(defn etaoin-fixture
  "A fixture for running browser tests with etaoin. Used in tandem with deftest-etaoin."
  [f]
  (if-not (test/remote-test?)
    (binding [*driver* (atom (ea/chrome {:headless true
                                         :size [1600 1000]}))]
      (f)
      (ea/quit @*driver*))
    (log/info "In a remote environment etaoin browser tests are not run")))

(defn new-project [project-name]
  (log/info "creating project" (pr-str project-name))
  (go "/" :silent true)
  (click {:css "#new-project.button"})
  (fill {:css "#create-project div.project-name input"} project-name)
  (click "//button[contains(text(),'Create Project')]")
  (when (test/remote-test?) (Thread/sleep 500))
  (wait-exists (str "//div[contains(@class,'project-title')]"
                    "//a[contains(text(),'" project-name "')]"))
  (wait-loading :pre-wait true))

(defn select-datasource [datasource-name]
  (wait-exists :enable-import)
  (when (exists? {:css (b/not-disabled "#enable-import")} :wait false)
    (click :enable-import))
  (let [datasource-item (str "//div[contains(@class,'datasource-item')]"
                             "//p[contains(text(),'" datasource-name "')]")]
    (click datasource-item)
    (wait-exists (str "//div[contains(@class,'datasource-item')"
                      "      and contains(@class,'active')]"
                      "//p[contains(text(),'" datasource-name "')]"))
    (Thread/sleep 100)))

(defn uppy-attach-files
  "Given a coll of file names in the resources dir, attach the files to
  uppy file element."
  [coll]
  (wait-exists "//button[contains(text(),'browse files')]")
  (fill {:css "input[name='files[]']"}
        (->> (for [s (util/ensure-vector coll)]
               (-> s io/resource .getFile URLDecoder/decode))
             (str/join "\n"))
        :delay 200))
