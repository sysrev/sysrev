(ns sysrev.memcached.interface-test
  (:require [clojure.test :refer (deftest is testing)]
            [com.stuartsierra.component :as component]
            [sysrev.memcached.interface :as mem]))

(deftest test-cache
  (let [system-map (component/system-map
                    :memcached (component/using
                                (mem/temp-client)
                                {:server :memcached-server})
                    :memcached-server (mem/temp-server))
        {:keys [memcached]} (component/start system-map)]
    (is (= 1 (mem/cache memcached "A" 5 1)) "Uncached value")
    (is (= 1 (mem/cache memcached "A" 5 2)) "Cached value")
    (is (= 2 (mem/cache memcached "B" 1 2)) "Uncached value")
    (Thread/sleep 1100)
    (is (= 3 (mem/cache memcached "B" 1 3)) "Expired value")
    (testing "cache-get"
      (is (= 1 (mem/cache-get memcached "A")))
      (is (= 3 (mem/cache-get memcached "B"))))
    (testing "cache-set"
      (testing "fresh key"
        (is (= true (mem/cache-set memcached "C" 5 "1")))
        (is (= "1" (mem/cache-get memcached "C"))))
      (testing "overwriting existing key"
        (is (= true (mem/cache-set memcached "C" 5 "2")))
        (is (= "2" (mem/cache-get memcached "C")))))))
