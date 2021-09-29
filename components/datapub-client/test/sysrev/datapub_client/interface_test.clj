(ns sysrev.datapub-client.interface-test
  (:require [datapub.main :refer (system-map)]
            [datapub.test :refer (load-ctgov-dataset! with-test-system)])
  (:use clojure.test
        sysrev.datapub-client.interface))

(defn test-system-config []
  {:get-system-map #(-> %
                        (update :pedestal assoc
                                :port 0
                                :sysrev-dev-key (str (java.util.UUID/randomUUID)))
                        system-map)})

#_:clj-kondo/ignore
(deftest test-get-dataset-entity
  (with-test-system [system (test-system-config)]
    (load-ctgov-dataset! system)
    (let [endpoint (str "http://localhost:"
                        (get-in system [:pedestal :bound-port])
                        "/api")]
      (is (= {:externalId "NCT04983004" :mediaType "application/json"}
             (get-dataset-entity 1 [:externalId :mediaType] :endpoint endpoint))))))

(deftest test-search-dataset
    (with-test-system [system (test-system-config)]
      (let [auth-token (get-in system [:pedestal :sysrev-dev-key])
            endpoint (str "ws://localhost:"
                          (get-in system [:pedestal :bound-port])
                          "/ws")
            ds-id (load-ctgov-dataset! system)]
        (is (= #{"NCT04982900" "NCT04982952" "NCT04982965" "NCT04982978"
                 "NCT04983004"}
               (->> (search-dataset
                     {:datasetId ds-id
                      :uniqueExternalIds true
                      :query
                      {:type "AND"
                       :text [{:search "clinic"
                               :useEveryIndex true}]}}
                     "externalId"
                     :auth-token auth-token
                     :endpoint endpoint)
                    (map :externalId)
                    (into #{})))))))
