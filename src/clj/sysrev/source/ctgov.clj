(ns sysrev.source.ctgov
  (:require [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [honeysql.helpers :as sqlh :refer [from join select where]]
            [orchestra.core :refer [defn-spec]]
            [sysrev.config :refer [env]]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.db.core :as db]
            [sysrev.shared.ctgov :as ctgov]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source
                                             import-source-articles import-source-impl]]))

(def ^:const source-name "CT.gov search")

(defn-spec get-entities (s/coll-of map?)
  [endpoint string?, ids (s/coll-of string?)]
  (map
   (fn [id]
     (let [{:keys [content externalId]} (dpc/get-dataset-entity
                                         id "content externalId"
                                         :endpoint endpoint)]
       {:content {:datapub {:entity-id id}
                  :types {:article-type "json"
                          :article-subtype "ctgov"}}
        :external-id externalId
        :primary-title (get-in (json/parse-string content keyword)
                               [:ProtocolSection :IdentificationModule :BriefTitle])}))
   ids))

(defmethod import-source :ctgov [sr-context _ project-id {:keys [entity-ids query]} options]
  (assert (map? query))
  (let [{:keys [max-import-articles]} env
        query (ctgov/canonicalize-query query)
        {:keys [filters search]} query]
    (cond (->> (source/project-sources project-id)
               (some
                (fn [{{:keys [filters search-term source]} :meta}]
                  (and (= source-name source)
                       (= query (ctgov/canonicalize-query
                                 {:filters filters
                                  :search search-term}))))))
          {:error {:message (format "Source already exists for %s" search)}}

          (> (count entity-ids) max-import-articles)
          {:error {:message (format "Too many entities (max %d; got %d)"
                                    max-import-articles (count entity-ids))}}

          :else (let [source-meta {:filters filters
                                   :search-term search
                                   :source source-name
                                   :results-count (count entity-ids)}]
                  (import-source-impl
                   sr-context project-id source-meta
                   {:types {:article-type "json"
                            :article-subtype "ctgov"}
                    :get-article-refs (constantly entity-ids)
                    :get-articles
                    (partial get-entities (get-in sr-context [:config :datapub-api]))}
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
        query (ctgov/query->datapub-input
               {:filters filters :search search-term})]
    (->> (dpc/search-dataset query "externalId id" :endpoint (:datapub-ws config))
         (remove (comp prev-article-ids :externalId))
         (map :id))))

(defmethod source/re-import source-name
  [{:keys [sr-context] :as request} project-id {:keys [source-id] :as source}]
  (source/alter-source-meta source-id #(assoc % :importing-articles? true))
  (source/set-import-date source-id)
  (future
    (import-source-articles
     request project-id source-id
     {:types {:article-type "json" :article-subtype "ctgov"}
      :article-refs (get-new-articles-available
                     source :config (:config sr-context))
      :get-articles (partial get-entities (get-in sr-context [:config :datapub-api]))}
     {:threads 1}))
  {:source-id source-id})
