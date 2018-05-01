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
      (stop-webdriver)
      (Thread/sleep 500)))

(defn go-route [path & [wait-ms]]
  (let [local? (boolean
                (= (:host (get-selenium-config))
                   "localhost"))
        wait-ms (or wait-ms (if local? 600 1000))
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

(defn wait-until-exists
  "Given a query q, wait until the element it represents exists"
  [q & [timeout interval]]
  (let [timeout (or timeout 10000)
        interval (or interval 200)]
    (Thread/sleep 350)
    (taxi/wait-until
     #(taxi/exists? q)
     timeout interval)))

(defn wait-until-displayed
  "Given a query q, wait until the element it represents exists
  and is displayed"
  [q & [timeout interval]]
  (let [timeout (or timeout 10000)
        interval (or interval 200)]
    (Thread/sleep 350)
    (taxi/wait-until
     #(and (taxi/exists? q)
           (taxi/displayed? q))
     timeout interval)))

(defn panel-name
  [panel-keys]
  (str/join "_" (map name panel-keys)))

(defn wait-until-panel-exists
  [panel-keys]
  (wait-until-exists {:css (str "div[id='" (panel-name panel-keys) "']")}))

(defn panel-exists? [panel-keys]
  (taxi/exists? {:css (str "div[id='" (panel-name panel-keys) "']")}))

;; TODO: "loader" class alone doesn't indicate loading
;; needs class "active" along with "loader" to render loading animation
;;
;; this will hang if called while a non-active "loader" element is present
(defn wait-until-loading-completes
  [& [timeout interval]]
  (let [timeout (or timeout 10000)
        interval (or interval 200)]
    (Thread/sleep 350)
    (taxi/wait-until
     #(not (taxi/exists?
            {:xpath "//div[contains(@class,'loader') and contains(@class,'active')]"}))
     timeout interval)))

(defn current-project-id []
  (let [url (taxi/current-url)
        [_ id-str] (re-matches #".*/p/(\d+)/?.*" url)]
    (when id-str
      (parse-integer id-str))))

(defn go-project-route [suburi & [project-id]]
  (let [project-id (or project-id (current-project-id))]
    (assert (integer? project-id))
    (go-route (str "/p/" project-id suburi))))
