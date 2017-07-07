(ns sysrev.custom.facts
  (:require [clojure-csv.core :as csv]
            [clojure.java.io :as io]
            [sysrev.util :refer [parse-number]]
            [sysrev.shared.util :refer [map-values]]
            [sysrev.import.pubmed :as pm]
            [sysrev.db.core :refer [do-query do-execute clear-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.db.articles :as articles]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [sysrev.shared.spec.web-api :as swa]))

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

(defn delete-article-locations [project-id source]
  (-> (delete-from [:article-location :al])
      (where [:and
              [:= :source source]
              [:exists
               (-> (select :*)
                   (from [:article :a])
                   (where [:and
                           [:= :a.article-id :al.article-id]
                           [:= :a.project-id project-id]]))]])
      do-execute))

(defn import-pmid-nct-arms-to-project
  "Imports into project a sequence of entries for PubMed articles associated
   with an NCT identifier and a trial arm for the NCT entry."
  [arm-imports project-id]
  (assert (s/valid? ::swa/nct-arm-imports arm-imports)
          (s/explain-str ::swa/nct-arm-imports arm-imports))
  (try
    (doseq [entry arm-imports]
      (let [{:keys [pmid nct arm-name arm-desc]} entry]
        (log/info "importing project article #"
                  (project/project-article-count project-id))
        (when-let [article (pm/fetch-pmid-entry pmid)]
          (doseq [[k v] article]
            (when (or (nil? v)
                      (and (coll? v) (empty? v)))
              (log/info (format "* field `%s` is empty" (pr-str k)))))
          (when-let [article-id (articles/add-article
                                 (-> article
                                     (dissoc :locations)
                                     (assoc :nct-arm-name arm-name)
                                     (assoc :nct-arm-desc arm-desc))
                                 project-id)]
            (-> (insert-into :article-location)
                (values [{:article-id article-id
                          :source "nct"
                          :external-id nct}])
                do-execute)
            (when (not-empty (:locations article))
              (-> (insert-into :article-location)
                  (values
                   (->> (:locations article)
                        (mapv #(assoc % :article-id article-id))))
                  do-execute))))))
    (finally
      (clear-project-cache project-id))))
