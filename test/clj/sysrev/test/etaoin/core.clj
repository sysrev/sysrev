(ns sysrev.test.etaoin.core
  (:require [cheshire.core :refer [generate-stream]]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest]]
            [clojure.tools.logging :as log]
            [etaoin.api
             :as
             etaoin
             :refer
             [format-date get-logs get-pwd get-source join-path screenshot]]
            [sysrev.test.browser.core :refer [cleanup-test-user!]]
            [sysrev.test.core :refer [get-selenium-config remote-test?]])
  (:import java.util.Date))

(defonce ^:dynamic *driver* (atom {}))
(defonce ^:dynamic *cleanup-users* (atom {}))

(def root-url (subs (:url (get-selenium-config)) 0 (- (count (:url (get-selenium-config))) 1)))

(defn setup-visual-chromedriver!
  "Used to setup for testing at repl"
  []
  (reset! *driver* (etaoin/chrome)))

(defn go [driver relative-url]
  (log/info "Navigating to " (str root-url relative-url))
  (etaoin/go driver (str root-url relative-url)))

(defn click [driver query]
  (etaoin/with-wait-timeout 15
    (etaoin/wait-absent driver {:css "div.ui.loader.active"}))
  (etaoin/wait-enabled driver query)
  (etaoin/click driver query))

(defn fill [driver query string]
  (etaoin/with-wait-timeout 15
    (etaoin/wait-absent driver {:css "div.ui.loader.active"}))
  (etaoin/wait-exists driver query)
  (etaoin/fill driver query string))

;; used for local debug purposes
(defn take-screenshot []
  (etaoin/screenshot @*driver* (str "./" (gensym) ".png")))

(defn- dump-logs
  [logs filename & [opt]]
  (generate-stream
   logs
   (io/writer filename)
   (merge {:pretty true} opt)))

(defn postmortem-handler
  "Based on etaoin postmortem handler, but using a different logging level"
  [driver {:keys [dir dir-src dir-img dir-log date-format]}]
  (let [dir     (or dir (get-pwd))
        dir-img (or dir-img dir)
        dir-src (or dir-src dir)
        dir-log (or dir-log dir)

        file-tpl "%s-%s-%s-%s.%s"

        date-format (or date-format "yyyy-MM-dd-HH-mm-ss")
        params      [(-> driver :type name)
                     (-> driver :host)
                     (-> driver :port)
                     (format-date (Date.) date-format)]

        file-img (apply format file-tpl (conj params "png"))
        file-src (apply format file-tpl (conj params "html"))
        file-log (apply format file-tpl (conj params "json"))

        path-img (join-path dir-img file-img)
        path-src (join-path dir-src file-src)
        path-log (join-path dir-log file-log)]

    (clojure.java.io/make-parents path-img)
    (clojure.java.io/make-parents path-src)
    (clojure.java.io/make-parents path-log)

    (log/errorf "Writing screenshot: %s" path-img)
    (screenshot driver path-img)

    (log/errorf "Writing HTML source: %s" path-src)
    (spit path-src (get-source driver))

    (when (etaoin/supports-logs? driver)
      (log/errorf "Writing console logs: %s" path-log)
      (dump-logs (get-logs driver) path-log))))

(defmacro with-postmortem
  "Copied from etaoin, but we are using a custom postmortem-handler"
  [driver opt & body]
  `(try
     ~@body
     (catch Exception e#
       (postmortem-handler ~driver ~opt)
       (throw e#))))

(defmacro deftest-etaoin
  "A macro for creating an etaoin browser test. Used in tandem with etaoin-fixture. A dynamic atom var of type vector, *cleanup-users*,  is used to cleanup tests users. Populate it within these tests with a `(swap! *cleanup-users* conj user)`"
  [name body]
  `(deftest ~name
     (binding [*cleanup-users* (atom [])]
       (try
         (with-postmortem @*driver* {:dir "/tmp/sysrev/etaoin"}
           (etaoin/with-wait-timeout 5
             ~body))
         (finally
           (doall
            (mapv #(cleanup-test-user! :email (:email %) :groups true) @*cleanup-users*)))))))

(defn etaoin-fixture
  "A fixture for running browser tests with etaoin. Used in tandem with deftest-etaoin."
  [f]
  (if-not (remote-test?)
    (binding [*driver* (atom (etaoin/chrome {:headless true}))]
      (f)
      (etaoin/quit @*driver*))
    (log/info "In a remote environment etaoin browser tests are not run")))
