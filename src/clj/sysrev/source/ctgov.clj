(ns sysrev.source.ctgov
  (:require [cheshire.core :as json]
            [honeysql.helpers :as sqlh :refer [select from where join]]
            [sysrev.config :refer [env]]
            [sysrev.datapub-client.interface :as datapub]
            [sysrev.db.core :as db]
            [sysrev.source.core :as source :refer [make-source-meta re-import]]
            [sysrev.source.interface :refer [after-source-import import-source
                                             import-source-articles import-source-impl]]))

(defn get-entities [ids]
  (map
   (fn [id]
     (let [content (-> (datapub/get-dataset-entity id "content")
                       :content
                       (json/parse-string keyword))]
       {:external-id id
        :primary-title (get-in content [:ProtocolSection :IdentificationModule :BriefTitle])}))
   ids))

(defmethod make-source-meta :ctgov [_ {:keys [search-term results-count]}]
  {:source "CT.gov search"
   :search-term search-term
   :results-count results-count})

(defmethod import-source :ctgov [_ project-id {:keys [entity-ids search-term]} options]
  (assert (string? search-term))
  (let [{:keys [max-import-articles]} env
        source-exists? (->> (source/project-sources project-id)
                            (filter #(and (= (get-in % [:meta :search-term]) search-term)
                                          (= (get-in % [:meta :source]) "CT.gov search"))))]
    (cond (seq source-exists?)
          {:error {:message (format "Source already exists for %s" search-term)}}
          (> (count entity-ids) max-import-articles)
          {:error {:message (format "Too many entities (max %d; got %d)"
                                    max-import-articles (count entity-ids))}}
          :else (let [source-meta (source/make-source-meta
                                   :ctgov {:search-term search-term
                                           :results-count (count entity-ids)})]
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
                              set)]
    (->> (datapub/search-dataset
          {:datasetId 1
           :uniqueExternalIds true
           :query
           {:type "AND"
            :text [{:search (:search-term meta)
                    :useEveryIndex true}]}}
          "id"
          :endpoint (:datapub-ws env))
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
