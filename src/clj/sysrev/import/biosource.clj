(ns sysrev.import.biosource
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [sysrev.util :as util]))

(defn important-terms
  "Given a coll of pmids, return a map of important term counts from biosource"
  [pmids]
  (-> (http/post "http://api.insilica.co/sysrev/importance"
                 {:content-type "application/json"
                  :body (json/write-str pmids)})
      :body
      (json/read-str :key-fn keyword)))

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


