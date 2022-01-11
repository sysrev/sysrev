(ns sysrev.test.core
  (:require
   [clj-time.core :as time]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [kaocha.repl :as kr]
   [kaocha.testable :as kt]
   [medley.core :as medley]
   [orchestra.spec.test :as st]
   [prestancedesign.get-port :as get-port]
   [ring.mock.request :as mock]
   [sysrev.config :refer [env]]
   [sysrev.datasource.api :refer [ds-auth-key]]
   [sysrev.db.core :as db]
   [sysrev.main :as main]
   [sysrev.payment.plans :as plans]
   [sysrev.postgres.interface :as pg]
   [sysrev.test.fixtures :as test-fixtures]
   [sysrev.user.interface :as user]
   [sysrev.util :as util :refer [in? shell]]
   [sysrev.web.core :as web]))

(def test-dbname "sysrev_auto_test")

(defonce test-system (atom nil))

(defn execute! [system sqlmap]
  (-> system :postgres :datasource
      (pg/execute! sqlmap)))

(defn execute-one! [system sqlmap]
  (-> system :postgres :datasource
      (pg/execute-one! sqlmap)))

(defn plan [system sqlmap]
  (-> system :postgres :datasource
      (pg/plan sqlmap)))

(defn sysrev-handler [system]
  (web/sysrev-handler (:web-server system)))

(defn cpu-count []
  (.availableProcessors (Runtime/getRuntime)))

(defn get-default-threads []
  (max 4 (quot (cpu-count) 2)))

(defn get-selenium-config [system]
  (let [{:keys [protocol host port] :as config}
        (or (:selenium env)
            {:protocol "http"
             :host "localhost"
             :port (-> system :web-server :bound-port)})]
    (assoc config
           :url (str protocol "://" host (if port (str ":" port) "") "/"))))

(defn full-tests? []
  (let [{:keys [sysrev-full-tests profile]} env]
    (boolean
     (or (= profile :dev)
         (not (in? [nil "" "0" 0] sysrev-full-tests))))))

(defn test-profile? []
  (in? [:test :remote-test] (-> env :profile)))

(defn remote-test? []
  (= :remote-test (-> env :profile)))

(defn test-db-shell-args [& [postgres-overrides]]
  (let [{:keys [dbname user host port]}
        (merge (:postgres env) postgres-overrides)]
    ["-h" (str host) "-p" (str port) "-U" (str user) (str dbname)]))

(defn db-shell [cmd & [extra-args postgres-overrides]]
  (let [args (concat [cmd] (test-db-shell-args postgres-overrides) extra-args)]
    (log/info "db-shell:" (->> args (str/join " ") pr-str))
    (apply shell args)))

(defmacro with-test-system [[name-sym] & body]
  `(binding [db/*active-db* (atom nil)
             db/*conn* nil
             db/*query-cache* (atom {})
             db/*query-cache-enabled* (atom false)
             db/*transaction-query-cache* nil]
     (let [postgres# (:postgres env)
           system# (swap!
                    test-system
                    (fn [sys#]
                      (or sys#
                          (do (st/instrument)
                              (-> (main/start-non-global!
                                   :config
                                   (medley/deep-merge
                                    env
                                    {:datapub-embedded true
                                     :server {:port (get-port/get-port)}})
                                   :postgres-overrides
                                   {:create-if-not-exists? true
                                    :dbname (str test-dbname (rand-int Integer/MAX_VALUE))
                                    :embedded? true
                                    :host "localhost"
                                    :port 0
                                    :user "postgres"}
                                   :system-map-f
                                   #(-> (apply main/system-map %&)
                                        (dissoc :scheduler)))
                                  test-fixtures/load-all-fixtures!)))))
           ~name-sym system#]
       (reset! db/*active-db* (:postgres system#))
       (binding [env (merge env (:config system#))]
         (do ~@body)))))

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

(defn test-namespaces
  "Returns a seq test namespaces for the given config as keywords, not symbols.

  Config is loaded by `kaocha.repl/config`.

  Adapted from https://www.juxt.pro/blog/parallel-kaocha"
  [& [extra-config]]
  (->> (kr/test-plan extra-config)
       kt/test-seq
       (filter #(= :kaocha.type/ns (:kaocha.testable/type %)))
       (map :kaocha.testable/id)
       sort))

(defn rotating-subset
  "Returns a subset of the items from a coll

  `index`: The zero-based index of the desired subset
  `total-subsets`: The total number of subsets
  `coll`: The coll to produce subsets from"
  [index total-subsets coll]
  (keep-indexed
   (fn [i item]
     (when (= index (rem i total-subsets))
       item))
   coll))

(defn run-test-subset! [{:keys [extra-config index total-subsets]}]
  (let [nses (rotating-subset index total-subsets (test-namespaces extra-config))
        result (apply kr/run (concat nses [(or extra-config {})]))]
    (System/exit (if (or (pos? (:kaocha.result/error result))
                         (pos? (:kaocha.result/fail result)))
                   1
                   0))))

(def test-suites #{:e2e :integration :skip :unit})

(defn find-orphaned-tests!
  "Find any tests that aren't labeled with meta in `test-suites`.

  E.g., each test should look like this:
  `(deftest ^:unit test-abc ,,,)`
  or:
  `(deftest ^:skip test-abc ,,,)`

  This will find tests that have missing or invalid meta:
  `(deftest test-xyz ,,,)`
  `(deftest ^:unt test-xyz ,,,)`"
  [& [extra-config]]
  (->> (kr/test-plan extra-config)
       kt/test-seq
       (filter (fn [{:kaocha.testable/keys [meta type]}]
                 (and (= :kaocha.type/var type)
                      (every? (complement meta) test-suites))))
       (map :kaocha.testable/id)))

(defn find-orphaned-tests-cli! [& [extra-config]]
  (let [tests (find-orphaned-tests! extra-config)]
    (when (seq tests)
      (doseq [t tests]
        (prn t))
      (System/exit 1))))
