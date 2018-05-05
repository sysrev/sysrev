(ns sysrev.test.browser.core
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as t]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [cljs.build.api :as cljs]
            [clj-webdriver.driver :as driver]
            [clj-webdriver.taxi :as taxi]
            [sysrev.config.core :refer [env]]
            [sysrev.test.core :refer [default-fixture get-selenium-config wait-until]]
            [sysrev.db.users :as users]
            [sysrev.stripe :as stripe]
            [sysrev.shared.util :refer [parse-integer]])
  (:import [org.openqa.selenium.chrome ChromeOptions ChromeDriver]
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

(defn db-connected? []
  (= "localhost" (:host (get-selenium-config))))

(defn start-webdriver [& [restart?]]
  (if (and @active-webdriver (not restart?))
    @active-webdriver
    (do (when @active-webdriver
          (try (taxi/quit) (catch Throwable e nil)))
        (reset! active-webdriver
                (let [opts (doto (ChromeOptions.)
                             (.addArguments
                              ["window-size=1920,1080"
                               "headless"]))
                      chromedriver (ChromeDriver.
                                    (doto (DesiredCapabilities. (DesiredCapabilities/chrome))
                                      (.setCapability ChromeOptions/CAPABILITY opts)))
                      driver (driver/init-driver {:webdriver chromedriver})]
                  (taxi/set-driver! driver))))))

(defn start-visual-webdriver [& [restart?]]
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

(def test-login
  {:email "browser+test@insilica.co"
   :password "1234567890"})

(defn delete-test-user [& {:keys [email]
                           :or {email (:email test-login)}}]
  (try
    (when-let [user (users/get-user-by-email email)]
      (stripe/delete-customer! user))
    (catch Throwable t
      nil))
  (users/delete-user-by-email email))

(defn create-test-user [& {:keys [email password project-id]
                           :or {email (:email test-login)
                                password (:password test-login)
                                project-id 100}}]
  (delete-test-user :email email)
  (users/create-user email password :project-id project-id))

(defn wait-until-exists
  "Given a query q, wait until the element it represents exists"
  [q & [timeout interval]]
  (let [timeout (or timeout 10000)
        interval (or interval 50)]
    (Thread/sleep 10)
    (taxi/wait-until
     #(taxi/exists? q)
     timeout interval)))

(defn wait-until-displayed
  "Given a query q, wait until the element it represents exists
  and is displayed"
  [q & [timeout interval]]
  (let [timeout (or timeout 10000)
        interval (or interval 50)]
    (Thread/sleep 10)
    (taxi/wait-until
     #(and (taxi/exists? q)
           (taxi/displayed? q))
     timeout interval)))

(defn wait-until-loading-completes
  [& [timeout interval]]
  (let [timeout (or timeout 10000)
        interval (or interval 50)]
    (Thread/sleep 25)
    (taxi/wait-until
     #(not (taxi/exists?
            {:xpath "//div[contains(@class,'loader') and contains(@class,'active')]"}))
     timeout interval)))

(defn init-route [& [path wait-ms]]
  (let [path (or path "/")
        local? (boolean
                (= (:host (get-selenium-config))
                   "localhost"))
        wait-ms (or wait-ms 500)
        full-url (str (:url (get-selenium-config))
                      (if (= (nth path 0) \/)
                        (subs path 1) path))]
    (log/info "loading:" full-url)
    (taxi/to full-url)
    (Thread/sleep wait-ms)
    (wait-until-loading-completes)))

(defn go-route [path & [wait-ms]]
  (let [wait-ms (or wait-ms 250)
        js-str (format "sysrev.nav.set_token(\"%s\");" path)]
    (log/info "navigating:" path)
    (taxi/execute-script js-str)
    (Thread/sleep wait-ms)
    (wait-until-loading-completes)))

(defn webdriver-fixture-once
  [f]
  (do (when (and (= "localhost" (:host (get-selenium-config)))
                 (not= :test (-> env :profile)))
        (build-cljs!))
      (f)))

(defn webdriver-fixture-each
  [f]
  (do (when (:safe (get-selenium-config))
        (create-test-user))
      (start-webdriver)
      (f)
      (stop-webdriver)
      (Thread/sleep 100)))

(defn set-input-text [q text & {:keys [delay] :or {delay 25}}]
  (wait-until-exists q)
  (taxi/clear q)
  (Thread/sleep delay)
  (taxi/input-text q text)
  (Thread/sleep delay))

(defn exists? [q & {:keys [wait?] :or {wait? true}}]
  (when wait?
    (wait-until-exists q))
  (taxi/exists? q))

(defn click [q & {:keys [if-not-exists delay displayed?]
                  :or {if-not-exists :wait
                       delay 25
                       displayed? false}}]
  (when (= if-not-exists :wait)
    (if displayed?
      (wait-until-displayed q)
      (wait-until-exists q)))
  (if (and (= if-not-exists :skip)
           (not (taxi/exists? q)))
    nil
    (do (taxi/click q)
        (Thread/sleep delay))))

(defn panel-name [panel-keys]
  (str/join "_" (map name panel-keys)))

(defn panel-exists? [panel & {:keys [wait?] :or {wait? true}}]
  (exists? {:css (str "div#" (panel-name panel))}
           :wait? wait?))

(defn login-form-shown? []
  (exists? {:css "div#login-register-panel"}))

(defn current-project-id []
  (let [url (taxi/current-url)
        [_ id-str] (re-matches #".*/p/(\d+)/?.*" url)]
    (when id-str
      (parse-integer id-str))))

(defn go-project-route [suburi & [project-id]]
  (wait-until-loading-completes)
  (let [project-id (or project-id (current-project-id))]
    (assert (integer? project-id))
    (go-route (str "/p/" project-id suburi))))
