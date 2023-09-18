(ns sysrev.pubmed-api.interface-test
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [sysrev.memcached.interface :as mem]
            [sysrev.pubmed-api.interface :as pubmed-api]))

(defn get-article-id [xml]
    (->> xml
         :content
         (pubmed-api/tag-val :PubmedData)
         :content
         (pubmed-api/tag-val :ArticleIdList)
         :content
         (pubmed-api/tag-val :ArticleId)
         :content))

(deftest test-get-fetches
  (let [system-map (component/system-map
                    :memcached (component/using
                                (mem/temp-client)
                                {:server :memcached-server})
                    :memcached-server (mem/temp-server))
        system (component/start system-map)
        opts (assoc system :ttl-sec 86400)
        angry-bees-pmids ["31203899" "31021106" "24860522" "24427187" "16999303"]]
    (try
      (is (= angry-bees-pmids
             (->> (pubmed-api/get-fetches opts angry-bees-pmids)
                  (mapcat get-article-id)))
          "uncached fetch")
      (is (= angry-bees-pmids
             (->> (pubmed-api/get-fetches opts angry-bees-pmids)
                  (mapcat get-article-id)))
          "cached fetch")
      (finally
        (component/stop system)))))
