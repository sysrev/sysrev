(ns datapub.auth-test
  (:require [buddy.sign.jwt :as jwt]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [datapub.auth :as auth]
            [datapub.test :as test]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.datapub-client.interface.queries :as dpcq]))

(defn ex! [system query & [variables opts]]
  (-> (test/execute! system query variables opts)
      :body
      test/throw-errors))

(defn dataset-read-jwt [system dataset-id]
  (let [now (quot (System/currentTimeMillis) 1000)]
    (-> {:dataset-id dataset-id
         :exp (+ now 900)
         :iat now
         :iss "datapub"
         :permissions ["read"]}
        (jwt/sign (auth/sysrev-dev-key system)))))

(defn rand-id [remove-f]
  (let [id (str (rand-int Integer/MAX_VALUE))]
    (if (remove-f id)
      (recur remove-f)
      id)))

(deftest test-jwt-dataset-read
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test"}})
                    (get-in [:data :createDataset :id]))]
      (is (= {:data {:dataset {:name "test" :public false}}}
             (ex (dpcq/q-dataset [:name :public]) {:id ds-id}
                 {:token (dataset-read-jwt system ds-id)}))
          "JWTs permit access to private datasets")
      (is (str/includes?
           (->> (test/execute!
                 system (dpcq/q-dataset [:name :public]) {:id ds-id}
                 {:token (dataset-read-jwt system (rand-id #{ds-id}))})
                :body :errors first :message)
           "Unauthorized")
          "Invalid JWTS do not allow access"))))

(deftest test-jwt-dataset-entity-read
  (test/with-test-system [system {}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test"}})
                    (get-in [:data :createDataset :id]))
          entity-id (-> (ex (dpcq/m-create-dataset-entity "id")
                            {:input
                             {:datasetId ds-id
                              :content (json/generate-string {:a 1})
                              :mediaType "application/json"}})
                        (get-in [:data :createDatasetEntity :id]))]
      (is (= {:data {:datasetEntity {:mediaType "application/json"}}}
             (ex (dpcq/q-dataset-entity [:mediaType]) {:id entity-id}
                 {:token (dataset-read-jwt system ds-id)}))
          "JWTs permit access to private datasets")
      (is (str/includes?
           (->> (test/execute!
                 system (dpcq/q-dataset-entity [:mediaType]) {:id entity-id}
                 {:token (dataset-read-jwt system (rand-id #{ds-id}))})
                :body :errors first :message)
           "not authorized")
          "Invalid JWTS do not allow access"))))

(deftest test-jwt-dataset-entity-content-download
  (test/with-test-system [system {:config {:pedestal {:port 0}}}]
    (let [ex (partial ex! system)
          ds-id (-> (ex (dpcq/m-create-dataset "id") {:input {:name "test"}})
                    (get-in [:data :createDataset :id]))
          content-url (-> (dpc/create-dataset-entity!
                           {:datasetId ds-id
                            :contentUpload (json/generate-string {:a 1})
                            :mediaType "application/json"}
                           "contentUrl"
                           :auth-token (auth/sysrev-dev-key system)
                           :endpoint (test/api-url system))
                          :contentUrl)]
      (is (string? content-url))
      (is (= {:a 1}
             (-> content-url
                 (http/get {:headers {:Authorization (str "Bearer " (dataset-read-jwt system ds-id))}
                            :as :json})
                 :body))
          "JWTs permit access to download entity content")
      (is (= "Forbidden"
             (-> content-url
                 (http/get {:headers {:Authorization (str "Bearer " (dataset-read-jwt system (rand-id #{ds-id})))}
                            :throw-exceptions false})
                 :body))
          "Invalid JWTs do not allow access"))))
