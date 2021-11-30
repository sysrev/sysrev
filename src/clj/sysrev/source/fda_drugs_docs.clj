(ns sysrev.source.fda-drugs-docs
  (:require [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [honeysql.helpers :as sqlh :refer [select from where join]]
            [orchestra.core :refer [defn-spec]]
            [sysrev.config :refer [env]]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.db.core :as db]
            [sysrev.shared.fda-drugs-docs :as fda-drugs-docs]
            [sysrev.source.core :as source :refer [make-source-meta re-import]]
            [sysrev.source.interface :refer [after-source-import import-source
                                             import-source-articles import-source-impl]]))

(defn capitalize-first [s]
  (if (empty? s)
    ""
    (apply str (Character/toUpperCase ^Character (first s)) (rest s))))

(defn capitalize-words [s]
  (->> (str/split s #" ")
       (map capitalize-first)
       (str/join " ")))

(defn title [{:keys [ApplicationDocsDescription ApplNo ApplType Products
                     ReviewDocumentType SponsorName]}]
  (str/join
   " — "
   [ApplNo
    ApplType
    (or (some-> ReviewDocumentType capitalize-words) ApplicationDocsDescription)
    SponsorName
    (str/join
     ", "
     (distinct
      (map #(str (:DrugName %) " / " (:ActiveIngredient %)) Products)))]))

(defn-spec get-entities (s/coll-of map?)
  [endpoint string?, ids (s/coll-of nat-int?)]
  (map
   (fn [id]
     (let [{:keys [groupingId id metadata]}
           #__ (dpc/get-dataset-entity id "groupingId id metadata" :endpoint endpoint)]
       ;; datasource-name doesn't show up in CLJS, so we are working
       ;; around that by including :types in the :content
       {:content {:datapub {:entity-id id}
                  :types {:article-type "pdf"
                          :article-subtype "fda-drugs-docs"}}
        :external-id groupingId
        :primary-title (some-> metadata (json/parse-string keyword) title)}))
   ids))

(defmethod make-source-meta :fda-drugs-docs
  [_ {:keys [filters search-term results-count]}]
  {:filters filters
   :search-term search-term
   :source "Drugs@FDA Application Documents search"
   :results-count results-count})

(defmethod import-source :fda-drugs-docs [_ project-id {:keys [entity-ids query]} options]
  (assert (map? query))
  (let [{:keys [max-import-articles]} env
        query (fda-drugs-docs/canonicalize-query query)
        {:keys [filters search]} query]
    (cond (->> (source/project-sources project-id)
               (some
                (fn [{{:keys [filters search-term source]} :meta}]
                  (and (= "Drugs@FDA Application Documents search" source)
                       (= query (fda-drugs-docs/canonicalize-query
                                 {:filters filters
                                  :search search-term}))))))
          {:error {:message (format "Source already exists for %s" search)}}

          (> (count entity-ids) max-import-articles)
          {:error {:message (format "Too many entities (max %d; got %d)"
                                    max-import-articles (count entity-ids))}}

          :else (let [source-meta (source/make-source-meta
                                   :fda-drugs-docs {:filters filters
                                                    :results-count (count entity-ids)
                                                    :search-term search})]
                  (import-source-impl
                   project-id source-meta
                   {:types {:article-type "pdf"
                            :article-subtype "fda-drugs-docs"}
                    :get-article-refs (constantly entity-ids)
                    :get-articles
                    (partial get-entities (get-in options [:web-server :config :datapub-api]))}
                   options)))))

(defn get-new-articles-available [{:keys [source-id meta]} & {:keys [config]}]
  (let [prev-article-ids (-> (select :article-data.external-id)
                             (from [:article-source :asrc])
                             (join :article [:= :asrc.article-id :article.article-id]
                                   :article-data [:= :article.article-data-id :article-data.article-data-id])
                             (where [:= :asrc.source-id source-id])
                             db/do-query
                             (->> (map :external-id))
                             set)
        {:keys [filters search-term]} meta
        query (fda-drugs-docs/query->datapub-input
               {:filters filters :search search-term})]
    (->> (dpc/search-dataset query "groupingId id" :endpoint (:datapub-ws config))
         (remove (comp prev-article-ids :groupingId))
         (map :id))))

(defmethod re-import "Drugs@FDA Application Documents search"
  [project-id {:keys [source-id] :as source} {:keys [web-server]}]
  (let [do-import (fn []
                    (->> (import-source-articles
                          project-id source-id
                          {:types {:article-type "pdf" :article-subtype "fda-drugs-docs"}
                           :article-refs (get-new-articles-available
                                          source :config (:config web-server))
                           :get-articles (partial get-entities (get-in web-server [:config :datapub-api]))}
                          {:threads 1})
                         (after-source-import project-id source-id)))]
    (source/alter-source-meta source-id #(assoc % :importing-articles? true))
    (source/set-import-date source-id)
    (future (do-import))
    {:source-id source-id}))
