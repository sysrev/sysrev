(ns sysrev.pubmed-api.interface-test
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [sysrev.memcached.interface :as mem]
            [sysrev.pubmed-api.interface :as pubmed-api]))

(deftest test-get-search-results
  (let [system-map (component/system-map
                    :memcached (component/using
                                (mem/temp-client)
                                {:server :memcached-server})
                    :memcached-server (mem/temp-server))
        opts (-> (component/start system-map)
                 (assoc :ttl-sec 86400))]
    (is (= '("31203899" "31021106" "24860522" "24427187" "16999303")
           (pubmed-api/get-search-results opts "angry bees")))
    (is (= {} (pubmed-api/get-fetches opts (pubmed-api/get-search-results opts "angry bees"))))
    (is (= {} (pubmed-api/get-fetches opts (pubmed-api/get-search-results opts "angry bees"))))))
