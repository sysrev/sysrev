(ns sysrev.source.datasource
  (:require [clojure.tools.logging :as log]
            [sysrev.datasource.api :as ds-api]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defn process-datasource-entities
  [coll]
  (->> coll
       (map #(let [{:keys [id]} %]
               {:external-id id
                :primary-title (str "Datasource Entity: " id)}))
       (into [])))

(defmethod make-source-meta :datasource [_ {:keys [query]}]
  {:source "Datasource API" :query query})

(defmethod import-source :datasource [_ project-id {:keys [query entities]} {:as options}]
  (let [query-source (->> (source/project-sources project-id)
                          (filter #(= (get-in % [:meta :query]) query)))]
    ;; this source already exists
    (if (seq query-source)
      (do (log/warn "import-source RIS - non-empty query-source: " query-source)
          {:error {:message (str "Datsource GraphQL " query " already imported")}})
      ;; attempt to create a RIS citation
      (let [source-meta (source/make-source-meta :datasource {:query query})]
        (import-source-impl
         project-id source-meta
         {:types {:article-type "datasource"
                  :article-subtype "entity"}
          :get-article-refs (constantly entities)
          :get-articles process-datasource-entities}
         options)
        {:result true}))))
