(ns sysrev.test.e2e.core
  (:require
   [cheshire.core :refer [generate-stream]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [is]]
   [clojure.tools.logging :as log]
   [etaoin.api :as ea]
   [me.raynes.fs :as fs]
   [remvee.base64 :as base64]
   [sysrev.config :refer [env]]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.core :as test]
   [sysrev.util :as util])
  (:import
   (java.net URL URLDecoder)
   (java.util Date)))

(defn bytes->base64
  "Returns a base64-encoded string corresponding to `bytes`."
  ^String [^bytes bytes]
  (apply str (base64/encode bytes)))

;; for REPL evaluation
(def run-headless? (constantly false))

(defn run-headless? []
  (not (:test-browser-show env)))

(defn not-class [cls]
  (format "not([class*=\"%s\"])" cls))

(def not-active (not-class "active"))
(def not-disabled (not-class "disabled"))

(def loader-elements-css [".loader" ".loading"])

(defn root-url [& [system]]
  (let [{:keys [url]} (test/get-selenium-config system)]
    (subs url 0 (dec (count url)))))

(defn absolute-url [system path]
  (str (root-url system) path))

(defn js-execute [driver script]
  (ea/js-execute driver script))

(defn get-path [driver]
  (-> (ea/get-url driver) URL. .getPath))

(defn sysrev-url? [driver]
  (when-let [url (util/ignore-exceptions (ea/get-url driver))]
    (some #(str/includes? url %) #{"localhost" "sysrev"})))

(defn current-user-id [driver]
  (js-execute driver "return sysrev.state.identity.current_user_id(sysrev.state.core.re_frame_db_state());"))

(defn browser-console-logs [driver]
  (when (sysrev-url? driver)
    (try (not-empty (js-execute driver "return sysrev.base.get_console_logs();"))
         (catch Throwable _
           (log/warn "unable to read console logs")))))

(defn browser-console-warnings [driver]
  (when (sysrev-url? driver)
    (try (not-empty (js-execute driver "return sysrev.base.get_console_warnings();"))
         (catch Throwable _
           (log/warn "unable to read console warnings")))))

(defn browser-console-errors [driver]
  (when (sysrev-url? driver)
    (try (not-empty (js-execute driver "return sysrev.base.get_console_errors();"))
         (catch Throwable _
           (log/warn "unable to read console errors")))))

(defn log-console-messages [driver level]
  (let [level (or level :info)]
    (if-let [logs (browser-console-logs driver)]
      (log/logf level "browser console logs:\n\n%s" logs)
      (log/logp level "browser console logs: (none)"))
    (if-let [warnings (browser-console-warnings driver)]
      (log/logf level "browser console warnings:\n\n%s" warnings)
      (log/logp level "browser console warnings: (none)"))
    (if-let [errors (browser-console-errors driver)]
      (log/logf level "browser console errors:\n\n%s" errors)
      (log/logp level "browser console errors: (none)"))))

(defn check-browser-console-clean [driver]
  (is (empty? (browser-console-errors driver)) "errors in browser console" )
  (is (empty? (browser-console-warnings driver)) "warnings in browser console")
  (when-not (and (empty? (browser-console-errors driver))
                 (empty? (browser-console-warnings driver)))
    (log-console-messages driver :warn))
  nil)

(defn wait-exists [driver q & [timeout interval]]
  (ea/with-wait-timeout (or (some-> timeout (/ 1000.0)) 15)
    (ea/with-wait-interval (or (some-> interval (/ 1000.0)) 0.020)
      (ea/wait-exists driver q))))

(defn exists? [driver q & {:keys [wait] :or {wait true}}]
  (when wait (wait-exists driver q))
  (ea/exists? driver q))

(defn ajax-pending-requests [driver]
  (some-> (js-execute driver "return sysrev.loading.all_pending_requests();")
          (util/read-transit-str)))

(defn ajax-activity-duration
  "Query browser for duration in milliseconds that ajax requests have
  been active (positive) or inactive (negative)."
  [driver]
  (js-execute driver "return sysrev.loading.ajax_status();"))

(defn ajax-inactive?
  "Returns true if no ajax requests in browser have been active for
  duration milliseconds (default 30)."
  [driver & [duration]]
  (< (ajax-activity-duration driver) (- (or duration 30))))

(defn wait-until-loading-completes
  [driver]
  (ea/wait-predicate
   (fn []
     (and (ajax-inactive? driver)
          (not (some #(ea/visible? driver {:css %}) loader-elements-css)))))
  (Thread/sleep 30))

(defn go [{:keys [driver system]} relative-url]
  {:pre [(map? driver) (not-empty driver)]}
  (let [full-url (absolute-url system relative-url)
        init? (= "data:," (ea/get-url driver))]
    (if init?
      (log/info "loading" full-url)
      (log/info "navigating to" relative-url))
    (when-not init? (check-browser-console-clean driver))
    (if init?
      (ea/go driver full-url)
      (js-execute driver (format "sysrev.nav.set_token(\"%s\")" relative-url)))
    (wait-until-loading-completes driver)
    (check-browser-console-clean driver)))

(defn go-project [test-resources project-id & [project-relative-url]]
  (go test-resources (str "/p/" project-id project-relative-url)))

(defn refresh [driver]
  (doto driver
    check-browser-console-clean
    ea/refresh
    wait-until-loading-completes
    check-browser-console-clean))

(defn enabled? [driver q & {:keys [wait] :or {wait true}}]
  (when wait (wait-exists driver q))
  (ea/enabled? driver q))

;; used for local debug purposes
(defn take-screenshot [driver]
  (ea/screenshot driver (str "./" (gensym) ".png")))

(defn- dump-logs [logs filename & [opt]]
  (generate-stream
   logs
   (io/writer filename)
   (merge {:pretty true} opt)))

(defn postmortem-handler
  "Based on etaoin postmortem handler, but using a different logging level"
  [driver {:keys [dir dir-src dir-img dir-log date-format]}]
  (let [dir     (or dir (ea/get-pwd))
        dir-img (or dir-img dir)
        dir-src (or dir-src dir)
        dir-log (or dir-log dir)

        file-tpl "%s-%s-%s-%s.%s"

        date-format (or date-format "yyyy-MM-dd-HH-mm-ss")
        params      [(-> driver :type name)
                     (-> driver :host)
                     (-> driver :port)
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

    (when-not (and (empty? (browser-console-errors driver))
                   (empty? (browser-console-warnings driver)))
      (log-console-messages driver :error))

    (log/errorf "Writing screenshot: %s" path-img)
    (ea/screenshot driver path-img)

    (log/errorf "Writing HTML source: %s" path-src)
    (spit path-src (ea/get-source driver))

    (when (ea/supports-logs? driver)
      (log/errorf "Writing console logs: %s" path-log)
      (dump-logs (ea/get-logs driver) path-log))))

(defmacro with-postmortem
  "Copied from etaoin, but we are using a custom postmortem-handler"
  [driver opts & body]
  `(try ~@body
        (catch Exception e#
          (postmortem-handler ~driver ~opts)
          (throw e#))))

(defn try-wait
  "Runs a wait function in try-catch block to avoid exception on
  timeout; returns true on success, false on timeout."
  [wait-fn & args]
  (test/succeeds? (do (apply wait-fn args) true)))

(defmacro is*
  "Runs (is pred-form), then on failure runs (assert pred-form)."
  [pred-form]
  `(or (is ~pred-form)
       (assert ~pred-form)))

(defmacro is-soon
  "Runs (is* pred-form) after attempting to wait for pred-form to
  evaluate as logical true."
  [driver pred-form timeout interval]
  `(let [driver# ~driver
         pred# (fn [] ~pred-form)]
     (or (try-wait ea/wait-predicate pred# ~timeout ~interval)
       (when driver# (take-screenshot driver# :error)))
     (is* (pred#))))

(defmacro with-driver [[driver-sym opts] & body]
  `(let [opts# ~opts
         headless?# (:headless? opts# (run-headless?))]
     (doseq [driver-type# [:chrome]]
       (ea/with-driver driver-type#
         (merge
          {:headless headless?#
           :path-browser (when (.exists (io/file "chrome")) "./chrome")
           :size [1600 1000]}
          opts#)
         driver#
         (with-postmortem driver# {:dir "/tmp/sysrev/etaoin"}
           (let [~driver-sym driver#
                 result# (do ~@body)]
             (check-browser-console-clean driver#)
             result#))))))

(defmacro with-test-resources [[bindings opts] & body]
  `(let [opts# ~opts]
     (test/with-test-system [system# (:system opts#)]
       (with-driver [driver# (:driver opts#)]
         (let [~bindings {:driver driver# :system system#}]
           ~@body)))))

(defn new-project [{:keys [driver] :as test-resources} project-name]
  (log/info "creating project" (pr-str project-name))
  (go test-resources "/new")
  (doto driver
    (et/fill-visible {:css "#create-project div.project-name input"} project-name)
    (et/click-visible "//button[contains(text(),'Create Project')]")
    (ea/wait-exists (str "//div[contains(@class,'project-title')]"
                             "//a[contains(text(),'" project-name "')]"))
    wait-until-loading-completes))

(defn select-datasource [driver datasource-name]
  (wait-exists driver :enable-import)
  (when (exists? driver {:css (str "#enable-import:" not-disabled)} :wait false)
    (et/click-visible driver :enable-import))
  (let [datasource-item (str "//div[contains(@class,'datasource-item')]"
                             "//p[contains(text(),'" datasource-name "')]")]
    (et/click-visible driver datasource-item)
    (wait-exists driver (str "//div[contains(@class,'datasource-item')"
                             "      and contains(@class,'active')]"
                             "//p[contains(text(),'" datasource-name "')]"))))

;; http://blog.fermium.io/how-to-send-files-to-a-dropzone-js-element-in-selenium/
(defn dropzone-upload
  "Given a filename, upload it to dropzone"
  [driver filename]
  (let [base64-file (-> filename io/resource io/file util/slurp-bytes bytes->base64)
        upload-blob-js (when (seq base64-file)
                         (str "function base64toBlob(r,e,n){e=e||\"\",n=n||512;for(var t=atob(r),a=[],o=0;o<t.length;o+=n){for(var l=t.slice(o,o+n),h=new Array(l.length),b=0;b<l.length;b++)h[b]=l.charCodeAt(b);var v=new Uint8Array(h);a.push(v)}var c=new Blob(a,{type:e}); c.name='" (fs/base-name filename) "'; return c} "
                              (format "return sysrev.util.add_dropzone_file_blob(base64toBlob, '%s');" base64-file)))]
    (js-execute driver upload-blob-js)))

(defn uppy-attach-files
  "Given a coll of file names in the resources dir, attach the files to
  uppy file element."
  [driver coll]
  (doto driver
    (ea/wait-exists {:fn/has-class :uppy-Dashboard-input})
    (ea/fill {:fn/has-class :uppy-Dashboard-input}
             (->> (for [s (util/ensure-vector coll)]
                    (-> s io/resource .getFile URLDecoder/decode))
                  (str/join "\n")))))

(defn panel-name [panel-keys]
  (str/join "_" (map name panel-keys)))
