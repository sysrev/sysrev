(ns sysrev.source.pubmed
  (:require [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.pubmed :as pubmed]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.source.core :as source]
            [sysrev.source.import :as import]
            [sysrev.shared.util :as su :refer [in?]]))

(defn pubmed-get-articles [pmids]
  (->> pmids sort
       (partition-all (if pubmed/use-cassandra-pubmed? 300 40))
       (map #(if pubmed/use-cassandra-pubmed?
               (pubmed/fetch-pmid-entries-cassandra %)
               (pubmed/fetch-pmid-entries %)))
       (apply concat)
       (filter #(and %
                     (:public-id %)
                     (not-empty (:primary-title %))))))
