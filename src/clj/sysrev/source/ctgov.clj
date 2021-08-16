(ns sysrev.source.ctgov
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [sysrev.config :refer [env]]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defn dataset-entity [return]
  (str "query($id: PositiveInt!){datasetEntity(id: $id){" return "}}"))

(defn get-entity [id]
  (-> (:datapub-api env "https://www.datapub.dev/api")
      (http/post
       {:as :json
        :content-type :json
        :form-params
        {:query (dataset-entity "content")
         :variables {:id id}}})
      :body :data :datasetEntity))

(defn get-entities [ids]
  (map
   (fn [id]
     (let [content (-> (get-entity id)
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
