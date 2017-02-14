(ns sysrev.custom.facts
  (:require [clojure-csv.core :as csv]
            [clojure.java.io :as io]
            [sysrev.util :refer [parse-number]]
            [sysrev.shared.util :refer [map-values]]
            [sysrev.import.pubmed :as pm]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

(defn parse-pmid-nct-csv [path]
  (->>
   path io/file io/reader csv/parse-csv
   (drop-while #(nil? (parse-number (nth % 0))))
   (filter #(parse-number (nth % 2)))
   (mapv
    (fn [[_ nct pmid]]
      {:pmid (parse-number pmid)  :nct nct}))
   (group-by :pmid)
   (map-values #(map :nct %))))

(defn import-facts-pmids [path project-id]
  (let [pmid-ncts (parse-pmid-nct-csv path)]
    (pm/import-pmids-to-project (keys pmid-ncts) project-id)))

(defn import-facts-nct-links [path project-id]
  (let [pmid-ncts (parse-pmid-nct-csv path)]
    (doseq [pmid (keys pmid-ncts)]
      (let [ncts (distinct (get pmid-ncts pmid))
            article-ids (-> (q/select-article-by-external-id
                             "pubmed" (str pmid) [:article-id]
                             {:project-id project-id})
                            (->> do-query (map :article-id)))
            entries (for [aid article-ids nct ncts]
                      {:article-id aid
                       :source "nct"
                       :external-id nct})]
        (when (not-empty entries)
          (-> (sqlh/insert-into :article-location)
              (values (vec entries))
              do-execute))))))
