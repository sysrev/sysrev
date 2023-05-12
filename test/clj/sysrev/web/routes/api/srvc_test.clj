(ns sysrev.web.routes.api.srvc-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [sysrev.test.core :as test :refer [sysrev-handler]]
            [sysrev.util :as util]))

(defn get-events [system project-id api-token]
  (let [handler (sysrev-handler system)
        {:keys [body status] :as response}
        (-> (handler
             (-> (mock/request :get (str "/web-api/srvc-events"))
                 (mock/query-string {:api-token api-token
                                     :project-id project-id})
                 (mock/header "Accept" "application/json")
                 (mock/header "Authorization" (str "Bearer " api-token)))))]
    (if (<= 200 status 299)
      (as-> body $
        (apply str $)
        (str/split $ #"\n+")
        (remove str/blank? $)
        (map #(json/parse-string % keyword) $))
      (throw (ex-info (str "Unexpected status " status)
                      {:response response})))))

(defn upload-events [system project-id api-token events]
  (let [handler (sysrev-handler system)]
    (doseq [event events]
      (let [{:keys [status] :as response}
            (-> (handler
                 (-> (mock/request :post (str "/web-api/srvc-project/" project-id "/api/v1/upload"))
                     (mock/header "Accept" "application/json")
                     (mock/header "Authorization" (str "Bearer " api-token))
                     (mock/header "Content-Type" "application/json")
                     (mock/body (json/generate-string event)))))]
        (when-not (<= 200 status 299)
          (throw (ex-info (str "Unexpected status " status)
                          {:response response})))))))

(defn load-jsonl-resource [filename]
  (->> filename
       io/resource
       io/reader
       line-seq
       (remove str/blank?)
       (map #(json/parse-string % keyword))))

(deftest ^:integration upload-test
  (test/with-test-system [system {}]
    (let [handler (sysrev-handler system)
          {:keys [email password]} (test/create-test-user system)
          api-token (-> (handler
                         (-> (mock/request :get "/web-api/get-api-token")
                             (mock/query-string {:email email :password password})))
                        :body util/read-json :result :api-token)
          create-project-response
          (-> (handler
               (->  (mock/request :post "/web-api/create-project")
                    (mock/body (util/write-json
                                {:project-name "create-project-test"
                                 :api-token api-token}))
                    (mock/header "Content-Type" "application/json")))
              :body util/read-json)
          project-id (get-in create-project-response [:result :project :project-id])
          expected-events (->> (load-jsonl-resource "srvc-test/upload/expected.jsonl")
                               (map (fn [{:keys [hash] :as event}]
                                      [hash event]))
                               (into {}))
          expected-hashes (->> expected-events vals (map :hash) set)]
      (testing "uploading srvc events"
        (upload-events system project-id api-token
                       (load-jsonl-resource "srvc-test/upload/events.jsonl"))
        (let [actual-events (->> (get-events system project-id api-token)
                                 (map (fn [{:keys [hash] :as event}]
                                        [hash event]))
                                 (into {}))
              actual-hashes (->> actual-events vals (map :hash) set)]
          ;; These test similar things, but the earlier tests provide more succint
          ;; info for debugging.
          (is (= #{}
                 (set/difference actual-hashes expected-hashes)
                 (set/difference expected-hashes actual-hashes)))
          (is (= []
                 (->> (set/difference actual-hashes expected-hashes)
                      (map actual-events))))
          (is (= []
                 (->> (set/difference expected-hashes actual-hashes)
                      (map expected-events))))
          (is (= #{}
                 (set/difference (set (vals actual-events)) (set (vals expected-events)))
                 (set/difference (set (vals expected-events)) (set (vals actual-events))))))))))
