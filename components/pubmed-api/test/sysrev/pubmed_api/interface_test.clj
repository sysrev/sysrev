(ns sysrev.pubmed-api.interface-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [sysrev.file-util.interface :as file-util]
            [sysrev.memcached.interface :as mem]
            [sysrev.pubmed-api.interface :as pubmed-api]))

(defmacro with-system [[system-sym _opts] & body]
  `(let [system-map# (component/system-map
                      :memcached (component/using
                                  (mem/temp-client)
                                  {:server :memcached-server})
                      :memcached-server (mem/temp-server))
         system# (component/start system-map#)
         ~system-sym system#]
    (try
      ~@body
      (finally
        (component/stop system#)))))

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
  (with-system [system {}]
    (let [angry-bees-pmids ["31203899" "31021106" "24860522" "24427187" "16999303"]]
      (is (= angry-bees-pmids
             (->> (pubmed-api/get-fetches system angry-bees-pmids)
                  (mapcat get-article-id)))
          "uncached fetch")
      (is (= angry-bees-pmids
             (->> (pubmed-api/get-fetches system angry-bees-pmids)
                  (mapcat get-article-id)))
          "cached fetch"))))

(deftest test-get-pmc-links
  (with-system [system {}]
    (is (nil? (pubmed-api/get-pmc-links system "PMC7170517"))
        "article with no open-access links")
    (is (= {:format "tgz"
            :href "ftp://ftp.ncbi.nlm.nih.gov/pub/pmc/oa_package/fc/a8/PMC4741700.tar.gz"}
           (-> (pubmed-api/get-pmc-links system "PMC4741700")
               first
               (select-keys [:format :href])))
        "article with one open-access link")))

(deftest test-pmc-file-nxml
  (file-util/with-temp-file [PMC4741700 {:prefix "sysrev-pubmed-api"
                                         :suffix ".tar.gz"}]
    (pubmed-api/download-pmc-file "ftp://ftp.ncbi.nlm.nih.gov/pub/pmc/oa_package/fc/a8/PMC4741700.tar.gz" (fs/file PMC4741700))
    (is (= "INTRODUCTION"
           (-> (pubmed-api/get-pmc-file-nxml (fs/file PMC4741700))
               pubmed-api/nxml-text
               (subs 0 12)))
        "Article nxml is downloaded and parsed to plaintext")))
