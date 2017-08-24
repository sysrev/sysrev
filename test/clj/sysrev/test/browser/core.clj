(ns sysrev.test.browser.core
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [cljs.build.api :as cljs]
            [clj-webdriver.driver :as driver]
            [clj-webdriver.taxi :as taxi]
            [sysrev.config.core :refer [env]]
            [sysrev.test.core :refer [default-fixture get-selenium-config]]
            [sysrev.db.users :refer
             [delete-user create-user get-user-by-email]]
            [clojure.string :as str])
  (:import [org.openqa.selenium.phantomjs PhantomJSDriver]
           [org.openqa.selenium.remote DesiredCapabilities CapabilityType]
           [org.openqa.selenium.logging LoggingPreferences LogType]
           [java.util.logging Level]))

(defn build-cljs!
  "Builds CLJS project for integration testing."
  []
  (cljs/build
   (cljs/inputs "src/cljs" "src/cljc")
   {:main "sysrev.core"
    :output-to "resources/public/integration/sysrev.js"
    :output-dir "resources/public/integration"
    :asset-path "/integration"
    :optimizations :none
    :pretty-print true
    :source-map true
    :source-map-timestamp true
    :static-fns true}))

(defonce active-webdriver (atom nil))

(defn start-webdriver [& [restart?]]
  (if (and @active-webdriver (not restart?))
    @active-webdriver
    (do (when @active-webdriver
          (try (taxi/quit) (catch Throwable e nil)))
        (reset!
         active-webdriver
         (let [^LoggingPreferences logs (LoggingPreferences.)
               _ (.enable logs LogType/DRIVER Level/WARNING)
               _ (.enable logs LogType/SERVER Level/WARNING)
               _ (.enable logs LogType/BROWSER Level/WARNING)
               _ (.enable logs LogType/CLIENT Level/WARNING)
               pjs (PhantomJSDriver.
                    (doto (DesiredCapabilities.)
                      (.setCapability
                       "phantomjs.cli.args"
                       (into-array String ["--ignore-ssl-errors=true"
                                           "--webdriver-loglevel=warn"]))
                      (.setCapability
                       "phantomjs.ghostdriver.cli.args"
                       (into-array String ["--logLevel=WARN"]))
                      (.setCapability
                       CapabilityType/LOGGING_PREFS
                       logs)))
               driver (driver/init-driver {:webdriver pjs})]
           (taxi/implicit-wait driver 3000)
           (taxi/set-driver! driver)
           (log/info "started phantomjs webdriver")
           driver)))))

(defn stop-webdriver []
  (when @active-webdriver
    (taxi/quit)
    (reset! active-webdriver nil)))

(def test-login
  {:email "browser+test@insilica.co"
   :password "1234567890"})

(defn delete-test-user []
  (let [email (-> test-login :email)]
    (when-let [user-id (-> (get-user-by-email email) :user-id)]
      (delete-user user-id))))

(defn create-test-user []
  (let [email (-> test-login :email)
        password (-> test-login :password)]
    (delete-test-user)
    (create-user email password :project-id 100)))

(defn webdriver-fixture-once
  [f]
  (do (when (= "localhost" (:host (get-selenium-config)))
        (build-cljs!))
      (f)))

(defn webdriver-fixture-each
  [f]
  (do (when (:safe (get-selenium-config))
        (create-test-user))
      (start-webdriver)
      (f)
      (stop-webdriver)))

(defn go-route [path & [wait-ms]]
  (let [local? (boolean
                (= (:host (get-selenium-config))
                   "localhost"))
        wait-ms (or wait-ms (if local? 500 800))
        full-url (str (:url (get-selenium-config))
                      (if (= (nth path 0) \/)
                        (subs path 1) path))]
    (log/info "loading " full-url)
    (taxi/to full-url)
    (Thread/sleep wait-ms)))

(defn element-rendered? [tag id]
  (not (nil?
        (try (taxi/find-element {:css (str (if tag tag "")
                                           "#" id)})
             (catch Throwable e
               nil)))))

(defn panel-rendered? [panel]
  (element-rendered? "div" (str/join "_" (map name panel))))

(defn login-form-shown? []
  (element-rendered? "div" "login-register-panel"))
