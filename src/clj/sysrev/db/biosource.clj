(ns sysrev.db.biosource
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]))

(defn- remote-query-test []
  (time
   (let [local-query (q/select-article-where
                      103 [:!= :a.public-id nil] [:a.public-id]
                      {:include-disabled? true})
         public-ids (->> local-query do-query (mapv :public-id))
         remote-query (-> (select (sql/qualify "G2P" :*)
                                  (sql/qualify "Gene" "Symbol"))
                          (from [:biosource.ncbi_gene_pubmed "G2P"])
                          (join [:biosource.ncbi_gene_info "Gene"]
                                [:=
                                 (sql/qualify "G2P" "GeneID")
                                 (sql/qualify "Gene" "GeneID")])
                          (where [:in (sql/qualify "G2P" "PubMed_ID") public-ids]))
         results (->> remote-query do-query)]
     {:local-query (sql/format local-query)
      :remote-query (sql/format remote-query)
      :results-count (count results)
      :results results})))
