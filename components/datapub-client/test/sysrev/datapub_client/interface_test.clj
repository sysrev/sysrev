(ns sysrev.datapub-client.interface-test
  (:require [datapub.main :refer (system-map)]
            [datapub.test :refer (load-ctgov-dataset! with-test-system)])
  (:use clojure.test
        sysrev.datapub-client.interface))

#_:clj-kondo/ignore
(deftest test-get-dataset-entity
  (let [k (str (java.util.UUID/randomUUID))]
    (with-test-system [system {:get-system-map #(-> %
                                                    (update :pedestal assoc
                                                            :port 0
                                                            :sysrev-dev-key k)
                                                    system-map)}]
      (load-ctgov-dataset! system)
      (let [endpoint (str "http://localhost:"
                          (get-in system [:pedestal :bound-port])
                          "/api")]
        (is (= {:externalId "NCT04983004" :mediaType "application/json"}
               (get-dataset-entity 1 [:externalId :mediaType] :endpoint endpoint)))))))
