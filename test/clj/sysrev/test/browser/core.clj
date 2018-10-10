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
            [sysrev.db.core :as db :refer [do-execute]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
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
  (not= "sysrev.com" (:host (get-selenium-config))))

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
  (let [{:keys [user-id] :as user}
        (users/get-user-by-email email)]
    (try
      (when user
        (stripe/delete-customer! user))
      (catch Throwable t
        nil))
    (when user-id
      (-> (delete-from :compensation-user-period)
          (where [:= :web-user-id user-id])
          do-execute))
    (users/delete-user-by-email email)))

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
        interval (or interval 25)]
    (Thread/sleep 25)
    (taxi/wait-until
     #(taxi/exists? q)
     timeout interval)))

(defn wait-until-displayed
  "Given a query q, wait until the element it represents exists
  and is displayed"
  [q & [timeout interval]]
  (let [timeout (or timeout 10000)
        interval (or interval 25)]
    (Thread/sleep 25)
    (taxi/wait-until
     #(and (taxi/exists? q)
           (taxi/displayed? q))
     timeout interval)))

(defn wait-until-loading-completes
  [& {:keys [timeout interval pre-wait]
      :or {timeout 10000
           interval 25
           pre-wait false}}]
  (when pre-wait
    (if (integer? pre-wait)
      (Thread/sleep pre-wait)
      (Thread/sleep 75)))
  (taxi/wait-until
   #(and
     (not (taxi/exists?
           {:xpath "//div[contains(@class,'loader') and contains(@class,'active')]"}))
     (not (taxi/exists?
           {:xpath "//div[contains(@class,'dimmer') and contains(@class,'active')]"})))
   timeout interval))

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
    (wait-until-loading-completes)
    (taxi/execute-script "sysrev.base.toggle_analytics(false);")))

(defn go-route [path & [wait-ms]]
  (let [wait-ms (or wait-ms 100)
        js-str (format "sysrev.nav.set_token(\"%s\");" path)]
    (wait-until-loading-completes :pre-wait true)
    (log/info "navigating:" path)
    (taxi/execute-script js-str)
    (Thread/sleep wait-ms)
    (wait-until-loading-completes)))

(defn webdriver-fixture-once
  [f]
  ;; this doesn't work anymore, build cljs manually
  #_ (do (when (and (= "localhost" (:host (get-selenium-config)))
                    (not= :test (-> env :profile)))
           (build-cljs!))
         (f))
  (f))

(defn webdriver-fixture-each
  [f]
  (let [local? (= "localhost" (:host (get-selenium-config)))
        cache? @db/query-cache-enabled]
    (do (when-not local?
          (reset! db/query-cache-enabled false))
        (when (:safe (get-selenium-config))
          (create-test-user))
        (start-webdriver)
        (f)
        (stop-webdriver)
        (when-not local?
          (reset! db/query-cache-enabled cache?))
        (Thread/sleep 25))))

(defn set-input-text [q text & {:keys [delay clear?] :or {delay 25 clear? true}}]
  (wait-until-exists q)
  (when clear? (taxi/clear q))
  (Thread/sleep delay)
  (taxi/input-text q text)
  (Thread/sleep delay))

(defn set-input-text-per-char
  [q text & {:keys [delay clear?] :or {delay 25 clear? true}}]
  (Thread/sleep delay)
  (doall (map (fn [c]
                (Thread/sleep delay)
                (taxi/input-text q (str c))) text))
  (Thread/sleep delay))

(defn input-text [q text & {:keys [delay] :as opts}]
  (apply set-input-text q text
         (->> (merge opts {:clear? false}) vec (apply concat))))

(defn exists? [q & {:keys [wait?] :or {wait? true}}]
  (when wait?
    (wait-until-exists q))
  (let [result (taxi/exists? q)]
    (when wait?
      (wait-until-loading-completes))
    result))

(defn click [q & {:keys [if-not-exists delay displayed?]
                  :or {if-not-exists :wait
                       delay 25
                       displayed? false}}]
  (Thread/sleep delay)
  (wait-until-loading-completes)
  (when (= if-not-exists :wait)
    (if displayed?
      (wait-until-displayed q)
      (wait-until-exists q)))
  (if (and (= if-not-exists :skip)
           (not (taxi/exists? q)))
    nil
    (do (taxi/click q)
        (Thread/sleep delay)
        (wait-until-loading-completes
         :pre-wait (if (<= delay 100) 50 false)))))

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

;; based on: https://crossclj.info/ns/io.aviso/taxi-toolkit/0.3.1/io.aviso.taxi-toolkit.ui.html#_clear-with-backspace
(defn backspace-clear
  "Hit backspace in input-element length times. Always returns true"
  [length input-element]
  (wait-until-exists input-element)
  (doall (repeatedly length
                     #(do (taxi/send-keys input-element org.openqa.selenium.Keys/BACK_SPACE)
                          (Thread/sleep 20))))
  true)

(defn go-project-route [suburi & [project-id]]
  (Thread/sleep 25)
  (let [project-id (or project-id (current-project-id))]
    (assert (integer? project-id))
    (go-route (str "/p/" project-id suburi))))

(defmacro deftest-browser [name & body]
  `(deftest ~name
     (try
       ~@body
       (catch Throwable e#
         (let [filename# (str "/tmp/" "screenshot" "-" (System/currentTimeMillis) ".png")]
           #_ (log/info "deftest-browser error: " (.getMessage e#))
           #_ (.printStackTrace e#)
           (taxi/take-screenshot :file filename#)
           (log/info "Saved screenshot:" filename#)
           (throw e#))))))
