(ns sysrev.test.core
  (:require [clj-time.core :as time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [kaocha.repl :as kr]
            [kaocha.testable :as kt]
            [kaocha.watch :as kw]
            [medley.core :as medley]
            [next.jdbc :as jdbc]
            [orchestra.spec.test :as st]
            [ring.mock.request :as mock]
            [sysrev.config :refer [env]]
            [sysrev.datasource.api :refer [ds-auth-key]]
            [sysrev.db.core :as db]
            [sysrev.db.migration :as migration]
            [sysrev.file-util.interface :as file-util]
            [sysrev.junit.interface :as junit]
            [sysrev.main :as main]
            [sysrev.payment.plans :as plans]
            [sysrev.postgres.interface :as pg]
            [sysrev.test.fixtures :as test-fixtures]
            [sysrev.user.interface :as user]
            [sysrev.util :as util]
            [sysrev.web.core :as web]))

(defonce test-systems (atom [nil []]))

(defn stop-test-systems! []
  (let [[systems] (swap! test-systems
                         (fn [[_ stack]]
                           [stack []]))]
    (doall (pmap component/stop systems))))

(defn execute! [system sqlmap]
  (-> system :postgres :datasource
      (pg/execute! sqlmap)))

(defn execute-one! [system sqlmap]
  (-> system :postgres :datasource
      (pg/execute-one! sqlmap)))

(defn sysrev-handler [system]
  (web/sysrev-handler (:web-server system)))

(defn get-selenium-config [system]
  (let [{:keys [test-remote]} (:config system)
        {:keys [protocol host port] :as config}
        (if (seq test-remote)
          test-remote
          {:protocol "http"
           :host "localhost"
           :port (-> system :web-server :bound-port)})]
    (assoc config
           :url (str protocol "://" host (if port (str ":" port) "") "/"))))

(defn start-test-system! []
  (let [system
        #__ (main/start-non-global!
             :config
             (medley/deep-merge
              env
              {:datapub-embedded true
               :server {:port 0}
               :sysrev-api-config {:env :dev :pedestal {:port 0}}})
             :postgres-overrides
             {:create-if-not-exists? true
              :embedded {:image "docker.io/library/postgres:11.15"}
              :host "localhost"
              :port 0
              :template-dbname "template_sysrev_test"
              :user "postgres"}
             :system-map-f
             #(-> (apply main/system-map %&)
                  (dissoc :scheduler)))]
    (let [{:keys [bound-port config]} (:postgres system)
          {:keys [template-dbname] :as opts} (-> (:postgres config)
                                                 (assoc :port bound-port))
          ds (jdbc/get-datasource (assoc opts :dbname template-dbname))]
      (test-fixtures/load-all-fixtures! ds)
      (binding [db/*active-db* (atom {:datasource ds})
                db/*conn* nil
                db/*query-cache* (atom {})
                db/*query-cache-enabled* (atom false)
                db/*transaction-query-cache* nil]
        (migration/ensure-updated-db)))
    (-> system :postgres :datasource-long-running test-fixtures/load-all-fixtures!)
    (migration/ensure-updated-db)
    system))

(defn recreate-db! [{:keys [postgres postgres-listener] :as system}]
  (let [listener (component/stop postgres-listener)]
    (pg/recreate-db! postgres)
    (assoc system :postgres-listener (component/start listener))))

(defmacro with-test-system [[name-sym opts] & body]
  `(binding [db/*active-db* (atom nil)
             db/*conn* nil
             db/*query-cache* (atom {})
             db/*query-cache-enabled* (atom false)
             db/*transaction-query-cache* nil]
     (let [opts# ~opts
           config# (or (:config opts#) env)
           postgres# (:postgres config#)
           remote?# (boolean (seq (:test-remote config#)))
           [system#] (if remote?#
                       {:config config#}
                       (swap!
                        test-systems
                        (fn [[_# stack#]]
                          (if (peek stack#)
                            [(peek stack#) (pop stack#)]
                            [nil stack#]))))]
       (try
         (let [system# (if system#
                         (recreate-db! system#)
                         (do (st/instrument)
                             (start-test-system!)))
               ~name-sym system#]
           (try
             (reset! db/*active-db* (:postgres system#))
             (binding [env (merge config# (:config system#))]
               (let [result# (do ~@body)]
                 (when-not remote?#
                   (swap! test-systems
                          (fn [[_# stack#]]
                            [nil (conj stack# system#)])))
                 result#))
             (catch Exception e#
               (component/stop system#)
               (throw e#))))
         (catch Exception e#
           (when system#
             (component/stop system#))
           (throw e#))))))

(defmacro completes? [form]
  `(do ~form true))

(defmacro succeeds? [form]
  `(try ~form (catch Throwable e# false)))

;; wait-until macro modified from
;; https://gist.github.com/kornysietsma/df45bbea3196adb5821b

(def default-timeout 10000)
(def default-interval 100)

(defn wait-until*
  ([name pred] (wait-until* name pred default-timeout default-interval))
  ([name pred timeout] (wait-until* name pred timeout default-interval))
  ([name pred timeout interval]
   (let [timeout (or timeout default-timeout)
         interval (or interval default-interval)
         die (time/plus (time/now) (time/millis timeout))]
     (loop [] (if-let [result (pred)] result
                      (do (Thread/sleep interval)
                          (if (time/after? (time/now) die)
                            (throw (ex-info (str "timed out waiting for " name)
                                            {:name name :timeout timeout :interval interval}))
                            (recur))))))))

(defmacro wait-until
  "Waits until function pred evaluates as true and returns result, or
  throws exception on timeout. timeout and interval may be passed as
  millisecond values, otherwise default values are used."
  ([pred] `(wait-until* ~(pr-str pred) ~pred))
  ([pred timeout] `(wait-until* ~(pr-str pred) ~pred ~timeout))
  ([pred timeout interval] `(wait-until* ~(pr-str pred) ~pred ~timeout ~interval)))

(defn change-user-plan! [system user-id plan-nickname]
  (let [plan (execute-one!
              system
              {:select :id
               :from :stripe-plan
               :where [:= :nickname plan-nickname]})]
    (when-not plan
      (throw (ex-info "Plan not found" {:nickname plan-nickname})))
    (plans/add-user-to-plan! user-id (:stripe-plan/id plan) "fake_subscription")))

(defn create-test-user [system
                        & [{:keys [email password]
                            :or {email "browser+test@insilica.co"
                                 password (util/random-id)}}]]
  (let [[name domain] (str/split email #"@")
        email (format "%s+%s@%s" name (util/random-id) domain)
        user (user/create-user email password)]
    (change-user-plan! system (:user-id user) "Basic")
    (assoc user :password password)))

;; from https://gist.github.com/cyppan/864c09c479d1f0902da5
(defn parse-cookies
  "Given a Cookie header string, parse it into a map"
  [cookie-string]
  (when cookie-string
    (into {} (for [cookie (str/split cookie-string #";")]
               (let [keyval (map str/trim (.split cookie "=" 2))]
                 [(keyword (first keyval)) (second keyval)])))))

(defn required-headers-params
  "Given a handler, return a map containing ring-session and csrf-token"
  [handler]
  (let [{:keys [headers body]} (handler (mock/request :get "/api/auth/identity"))
        {:keys [ring-session]} (-> (get headers "Set-Cookie") first parse-cookies)
        {:keys [csrf-token]} (util/read-transit-str body)]
    {:ring-session ring-session
     :csrf-token csrf-token}))

(defn required-headers
  "Given a handler, return a fn that acts on a
  request to add the headers required by the handler"
  [handler]
  (let [{:keys [ring-session csrf-token]} (required-headers-params handler)]
    (fn [request]
      (-> request
          (mock/header "x-csrf-token" csrf-token)
          (mock/header "Cookie" (str "ring-session=" ring-session))
          (mock/header "Content-Type" "application/transit+json")))))

(defn route-response-builder
  "Get the response from handler using request method at uri with
  optional map parameters. When method is :get, parameters are passing
  in query-string, when method is :post, parameters are passed in body
  as transit+json. Returns the body as a map"
  [handler required-headers-fn method uri & [parameters]]
  (let [request-params-fn (if (= :get method)
                            #(mock/query-string % parameters)
                            #(mock/body % (util/write-transit-str parameters)))]
    (util/read-transit-str
     (:body (handler (-> (mock/request method uri) request-params-fn required-headers-fn))))))

(defn route-response-fn
  "Return a fn of method, uri and options parameters for handling a mock
  request given a handler and required-headers-fn"
  [handler]
  (let [required-headers-fn (required-headers handler)]
    (fn [method uri & [parameters]]
      (route-response-builder handler required-headers-fn method uri parameters))))

(defn graphql-request
  "Make a request on app, returning the body as a clojure map"
  [system query & {:keys [api-key] :or {api-key (ds-auth-key)}}]
  (let [app (sysrev-handler system)
        body (-> (app (-> (mock/request :post "/graphql")
                          (mock/header "Authorization" (str "Bearer " api-key))
                          (mock/json-body {:query (util/gquery query)})))
                 :body)]
    (try (json/read-str body :key-fn keyword)
         (catch Exception _
           body))))

(def test-kind-order [:unit :integration :e2e :optional])
(def test-kinds (into #{} test-kind-order))

(defn tests-by-kind [& [extra-config]]
  (->> (kr/test-plan extra-config)
       kt/test-seq
       (reduce
        (fn [result {:kaocha.testable/keys [id meta type]}]
          (if (not= :kaocha.type/var type)
            result
            (update result
                    (into #{} (keep #(when (meta %) %) test-kinds))
                    (fnil conj #{}) ;; A set is used to deduplicate tests that are in more than one test suite
                    id)))
        {})))

(defn find-invalid-tests!
  "Find any tests that aren't labeled with meta in `test-kinds` or have meta
  from more than one kind.

  E.g., each test should look like this:
  `(deftest ^:unit test-abc ,,,)`

  This will return tests that have missing or invalid meta:
  `(deftest test-xyz ,,,)`
  `(deftest ^:unt test-xyz ,,,)`
  `(deftest ^:unit ^:e2e test-xyz ,,,)`"
  [& [extra-config]]
  (->> (tests-by-kind extra-config)
       (medley/filter-keys #(not= 1 (count %)))
       vals
       (apply concat)))

(defn find-invalid-tests-cli! [& [extra-config]]
  (log/info "Checking test kind assignments")
  (let [tests (find-invalid-tests! extra-config)]
    (when (seq tests)
      (log/info (str "Found tests with no valid test kind metadata (one of " (pr-str test-kinds) ") or that have metadata for more than one test kind"))
      (doseq [t tests]
        (log/info t))
      (System/exit 1)))
  (log/info "All tests are assigned to a valid test kind"))

(defn create-target-dir! []
  (file-util/create-directories! (file-util/get-path "target")))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn run-tests-cli! [& [{:keys [extra-config]}]]
  (find-invalid-tests-cli! extra-config)
  (create-target-dir!)
  (let [test-ids (tests-by-kind extra-config)
        get-tests (fn [kind] (get test-ids #{kind}))
        test-kinds (filter #(when-not (= :optional %)
                              (seq (get-tests %)))
                           test-kind-order)
        junit-target (file-util/get-path "target/junit.xml")]
    (cond
      (empty? test-ids)
      (do
        (log/error "No tests found")
        (System/exit 1))

      (:kaocha/watch? extra-config)
      (let [[exit-code finish!] (kw/run (kr/config extra-config))]
        (try
          @exit-code
          (catch InterruptedException _
            (finish!)
            @exit-code))
        (stop-test-systems!)
        (System/exit exit-code))

      :else
      (file-util/with-temp-files [junit-files
                                  {:num-files (count test-kinds)
                                   :prefix "junit-"
                                   :suffix ".xml"}]
        (doseq [[kind junit-file i] (map vector test-kinds junit-files (range))]
          (log/info "Running" (count (get-tests kind)) (name kind)
                    (if (< 1 (count (get-tests kind))) "tests" "test"))
          (let [kind-config (merge extra-config {:kaocha.plugin.junit-xml/target-file junit-file})
                {:kaocha.result/keys [error fail]}
                #__ (apply kr/run (concat (get-tests kind) [kind-config]))
                tests-passed? (not (or (pos? error) (pos? fail)))]
            (if tests-passed?
              (log/info (name kind) "tests passed")
              (do (log/error (name kind) "tests failed")
                  (junit/merge-files! junit-target (take (inc i) junit-files))
                  (System/exit 1)))))
        (junit/merge-files! junit-target junit-files)
        (stop-test-systems!)
        (System/exit 0)))))

(defn importing-articles? [system project-id]
  (->> (execute!
        system
        {:select [:meta]
         :from :project-source
         :where [:= :project-id project-id]})
       (some (comp :importing-articles? :project-source/meta))
       boolean))

(defn wait-not-importing? [system project-id & [timeout-ms]]
  (let [fut (future (while (importing-articles? system project-id)
                      (Thread/sleep 100))
                    true)
        result (deref fut (or timeout-ms 1000) false)]
    (future-cancel fut)
    result))
