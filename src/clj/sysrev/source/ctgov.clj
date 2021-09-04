(ns sysrev.source.ctgov
  (:require [cheshire.core :as json]
            [honeysql.helpers :as sqlh :refer [select from where join]]
            [sysrev.config :refer [env]]
            [sysrev.datapub-client.interface :as datapub]
            [sysrev.db.core :as db]
            [sysrev.shared.ctgov :as ctgov]
            [sysrev.source.core :as source :refer [make-source-meta re-import]]
            [sysrev.source.interface :refer [after-source-import import-source
                                             import-source-articles import-source-impl]]))

(defn get-entities [ids]
  (map
   (fn [id]
     (let [ps (-> (datapub/get-dataset-entity id "content"
                                              :endpoint (:datapub-api env))
                  :content
                  (json/parse-string keyword)
                  :ProtocolSection)]
       {:abstract (get-in ps [:DescriptionModule :BriefSummary])
        :external-id id
        :primary-title (get-in ps [:IdentificationModule :BriefTitle])
        :secondary-title (get-in ps [:IdentificationModule :OfficialTitle])}))
   ids))

(defmethod make-source-meta :ctgov
  [_ {:keys [filters search-term results-count]}]
  {:filters filters
   :search-term search-term
   :source "CT.gov search"
   :results-count results-count})

(defmethod import-source :ctgov [_ project-id {:keys [entity-ids query]} options]
  (assert (map? query))
  (let [{:keys [max-import-articles]} env
        query (ctgov/canonicalize-query query)
        {:keys [filters search]} query]
    (cond (->> (source/project-sources project-id)
               (some
                (fn [{{:keys [filters search-term source]} :meta}]
                  (and (= "CT.gov search" source)
                       (= query (ctgov/canonicalize-query
                                 {:filters filters
                                  :search search-term}))))))
          {:error {:message (format "Source already exists for %s" search)}}

          (> (count entity-ids) max-import-articles)
          {:error {:message (format "Too many entities (max %d; got %d)"
                                    max-import-articles (count entity-ids))}}

          :else (let [source-meta (source/make-source-meta
                                   :ctgov {:filters filters
                                           :results-count (count entity-ids)
                                           :search-term search})]
                  (import-source-impl
                   project-id source-meta
                   {:types {:article-type "json"
                            :article-subtype "ctgov"}
                    :get-article-refs (constantly entity-ids)
                    :get-articles get-entities}
                   options)))))

(defn get-new-articles-available [{:keys [source-id meta]}]
  (let [prev-article-ids (->> (-> (select :article-data.external-id)
                                  (from [:article-source :asrc])
                                  (join :article [:= :asrc.article-id :article.article-id]
                                        :article-data [:= :article.article-data-id :article-data.article-data-id])
                                  (where [:= :asrc.source-id source-id])
                                  db/do-query)
                              (map :external-id)
                              (filter number?)
                              set)
        {:keys [filters search-term]} meta
        query (ctgov/query->datapub-input
               {:filters filters :search search-term})]
    (->> (datapub/search-dataset query "id" :endpoint (:datapub-ws env))
         (map :id)
         (remove prev-article-ids))))

(defmethod re-import "CT.gov search" [project-id {:keys [source-id] :as source}]
  (let [do-import (fn []
                    (->> (import-source-articles
                           project-id source-id
                           {:types {:article-type "json" :article-subtype "ctgov"}
                            :article-refs (get-new-articles-available source)
                            :get-articles get-entities}
                           {:threads 1})
                         (after-source-import project-id source-id)))]
    (source/alter-source-meta source-id #(assoc % :importing-articles? true))
    (source/set-import-date source-id)
    (future (do-import))
    {:source-id source-id}))
