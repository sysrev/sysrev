(ns sysrev.biosource.importance
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer
             [do-query do-execute with-transaction
              with-project-cache clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [map-values]]
            [sysrev.config.core :as config]))

(defn project-important-terms [project-id]
  (with-project-cache
    project-id [:important-terms]
    (-> (select :entity-type :instance-name
                :instance-count :instance-score)
        (from :project-entity)
        (where [:= :project-id project-id])
        (->> do-query
             (group-by #(-> % :entity-type keyword))))))

(defn fetch-important-terms
  "Given a coll of pmids, return a map of important term counts from biosource"
  [pmids]
  (-> (http/post "http://api.insilica.co/sysrev/importance"
                 {:content-type "application/json"
                  :body (json/write-str pmids)})
      :body
      (json/read-str :key-fn keyword)))

(defn load-project-important-terms
  "Queries important terms for `project-id` from Insilica API
  and stores results in local database."
  [project-id]
  (let [max-count 100
        entities {:gene "gene", :mesh "mesh", :chemical "chemical"}
        {:keys [geneCounts chemicalCounts meshCounts]}
        (fetch-important-terms (project/project-pmids project-id))]
    (let [entries
          (->> [(->> geneCounts
                     (mapv (fn [{:keys [gene count tfidf]}]
                             {:entity-type (-> entities :gene)
                              :instance-name (:symbol gene)
                              :instance-count count
                              :instance-score tfidf}))
                     (sort-by :instance-count >)
                     (take max-count))
                (->> chemicalCounts
                     (mapv (fn [{:keys [term count tfidf]}]
                             {:entity-type (-> entities :chemical)
                              :instance-name term
                              :instance-count count
                              :instance-score tfidf}))
                     (sort-by :instance-count >)
                     (take max-count))
                (->> meshCounts
                     (mapv (fn [{:keys [term count tfidf]}]
                             {:entity-type (-> entities :mesh)
                              :instance-name term
                              :instance-count count
                              :instance-score tfidf}))
                     (sort-by :instance-count >)
                     (take max-count))]
               (apply concat)
               (filter #(and %
                             (-> % :entity-type string?)
                             (-> % :instance-name string?)
                             (or (-> % :instance-count integer?)
                                 (-> % :instance-count nil?))
                             (or (-> % :instance-score number?)
                                 (-> % :instance-score nil?))
                             (or (-> % :instance-count integer?)
                                 (-> % :instance-score number?))))
               (mapv #(assoc % :project-id project-id)))]
      (when (not-empty entries)
        (with-transaction
          (-> (delete-from :project-entity)
              (where [:= :project-id project-id])
              do-execute)
          (doseq [entries-group (partition-all 500 entries)]
            (-> (insert-into :project-entity)
                (values entries-group)
                do-execute))))
      (clear-project-cache project-id)
      nil)))

(defn schedule-important-terms-update [project-id]
  (future (load-project-important-terms project-id)))

(defn force-importance-update-all-projects []
  (let [project-ids (project/all-project-ids)]
    (log/info "Updating important terms for projects:"
              (pr-str project-ids))
    (doseq [project-id project-ids]
      (log/info "Loading for project #" project-id "...")
      (load-project-important-terms project-id))
    (log/info "Finished updating predictions for"
              (count project-ids) "projects")))

(defn map-coll->csv-string
  "Given a coll of maps of the same form, return a csv string"
  [coll]
  (let [headers (keys (first coll))]
    (str
     ;; headers
     (clojure.string/join "|" (map name headers)) "\n"
     ;; data
     (->> coll
          (map #(map % headers))
          (map (partial clojure.string/join "|"))
          (clojure.string/join "\n")))))

(defn extract-pmids-from-sysrev-raw-json-export
  "Given a string file descriptor for a SysRev_Raw_*.json file, return a vector of PMIDs"
  [f]
  (let [get-pmid (fn [article-json] (->> article-json
                                         :locations
                                         (filter #(= "pubmed" (:source %)))
                                         first
                                         :external-id
                                         util/parse-number))]
    (->>
     ;; extract the articles json
     (-> f
         (slurp)
         (json/read-str :key-fn keyword)
         :articles)
     ;; extract the pmids
     (mapv get-pmid)
     ;; remove any nil vals
     (filterv (comp not nil?)))))

;; create a terms map
;;  (def project-terms (important-terms (extract-pmids-from-sysrev-raw-json-export "/Users/james/Downloads/SysRev_Raw_269_20180315.json")))

;; create the chemicalCounts csv, with the top 100 terms
;;(spit "chemical-counts.csv" (map-coll->csv-string (reverse (sort-by :count (:chemicalCounts project-terms)))))
;; create the meshCounts csv
;;(spit "mesh-counts.csv" (map-coll->csv-string (reverse (sort-by :count (:meshCounts project-terms)))))
;; create the geneCounts csv
;;(spit "gene-counts.csv" (map-coll->csv-string (mapv (fn [gene-map] (merge (:gene gene-map) {:count (:count gene-map)})) (reverse (sort-by :count (:geneCounts project-terms))))))


