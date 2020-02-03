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
  {:source "Datasource: Query" :query query})

(defmethod import-source :datasource [_ project-id {:keys [query entities]} {:as options}]
  (let [query-source (->> (source/project-sources project-id)
                          (filter #(= (get-in % [:meta :query]) query)))]
    ;; this source already exists
    (if (seq query-source)
      (do (log/warn "import-source datasource query - non-empty query-source: " query-source)
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

(defmethod make-source-meta :datasource-dataset [_ {:keys [dataset-id dataset-name]}]
  {:source "Datasource: Dataset" :dataset-id dataset-id :dataset-name dataset-name})

(defmethod import-source :datasource-dataset [_ project-id {:keys [dataset-id entities dataset-name]} {:as options}]
  (let [dataset-source (->> (source/project-sources project-id)
                            (filter #(= (get-in % [:meta :dataset-id]) dataset-id)))]
    ;; this source already exists
    (if (seq dataset-source)
      (do (log/warn "import-source datasource-dataset - already imported dataset-id: " dataset-id)
          {:error {:message (str "Datsource Dataset id:" dataset-id " already imported")}})
      ;; create the datasource
      (let [source-meta (source/make-source-meta :datasource-dataset {:dataset-id dataset-id
                                                                      :dataset-name dataset-name})]
        (import-source-impl
         project-id source-meta
         {:types {:article-type "datasource"
                  :article-subtype "entity"}
          :get-article-refs (constantly entities)
          :get-articles process-datasource-entities}
         options)
        {:result true}))))

(defmethod make-source-meta :datasource-datasource [_ {:keys [datasource-id datasource-name]}]
  {:source "Datasource: Datasource" :datasource-id datasource-id :datasource-name datasource-name})

(defmethod import-source :datasource-datasource [_ project-id {:keys [datasource-id entities datasource-name]} {:as options}]
  (let [datasource-source (->> (source/project-sources project-id)
                               (filter #(= (get-in % [:meta :datasource-id]) datasource-id)))]
    ;; this source already exists
    (if (seq datasource-source)
      (do (log/warn "import-source datasource-datasource - already imported datasource-id: " datasource-id)
          {:error {:message (str "Datasource id:" datasource-id " already imported")}})
      ;; create the datasource
      (let [source-meta (source/make-source-meta :datasource-datasource {:datasource-id datasource-id
                                                                         :datasource-name datasource-name})]
        (import-source-impl
         project-id source-meta
         {:types {:article-type "datasource"
                  :article-subtype "entity"}
          :get-article-refs (constantly entities)
          :get-articles process-datasource-entities}
         options)
        {:result true}))))
