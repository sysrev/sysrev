(ns sysrev.source.fda-drugs-docs
  (:require [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [honeysql.helpers :as sqlh :refer [from join select where]]
            [orchestra.core :refer [defn-spec]]
            [sysrev.config :refer [env]]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.db.core :as db]
            [sysrev.shared.fda-drugs-docs :as fda-drugs-docs]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source
                                             import-source-articles import-source-impl]]))

(def ^:const source-name "Drugs@FDA Application Documents search")

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
  [endpoint string?, ids (s/coll-of string?)]
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

(defmethod import-source :fda-drugs-docs
  [sr-context _ project-id {:keys [entity-ids query]} options]
  (assert (map? query))
  (let [{:keys [max-import-articles]} env
        query (fda-drugs-docs/canonicalize-query query)
        {:keys [filters search]} query]
    (cond (->> (source/project-sources sr-context project-id)
               (some
                (fn [{{:keys [filters search-term source]} :meta}]
                  (and (= source-name source)
                       (= query (fda-drugs-docs/canonicalize-query
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
                   {:types {:article-type "pdf"
                            :article-subtype "fda-drugs-docs"}
                    :get-article-refs (constantly entity-ids)
                    :get-articles
                    (partial get-entities (get-in sr-context [:config :graphql-endpoint]))}
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

(defmethod source/re-import source-name
  [sr-context project-id {:keys [source-id] :as source}]
  (source/alter-source-meta source-id #(assoc % :importing-articles? true))
  (source/set-import-date source-id)
  (future
    (import-source-articles
     sr-context project-id source-id
     {:types {:article-type "pdf" :article-subtype "fda-drugs-docs"}
      :article-refs (get-new-articles-available
                     source :config (:config sr-context))
      :get-articles (partial get-entities (get-in sr-context [:config :graphql-endpoint]))}
     {:threads 1}))
  {:source-id source-id})
