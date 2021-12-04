(ns sysrev.source.datasource
  (:require [clojure.tools.logging :as log]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [medley.core :as medley]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.graphql.core :refer [fail with-datasource-proxy]]))

(defn process-datasource-entities [coll]
  (->> coll (mapv (fn [{:keys [id]}]
                    {:external-id id
                     :primary-title (str "Datasource Entity: " id)}))))

;;;
;;; datasource-query
;;;

(defmethod make-source-meta :datasource-query
  [_ {:keys [query]}]
  {:source "Datasource Query" :query query})

(defmethod import-source :datasource-query
  [request stype project-id {:keys [query entities]} & {:as options}]
  (if (seq (->> (source/project-sources project-id)
                (filter #(= (get-in % [:meta :query]) query))))
    (do (log/warnf "import-source %s - query %s already imported" stype (pr-str query))
        {:error {:message (format "Datasource query %s already imported" (pr-str query))}})
    (do (import-source-impl
         request project-id
         (source/make-source-meta stype {:query query})
         {:types {:article-type "datasource" :article-subtype "entity"}
          :get-article-refs (constantly entities)
          :get-articles process-datasource-entities}
         options)
        {:result true})))

(def import-ds-query
  ^ResolverResult 
  (fn [context {:keys [id query]} _]
    (let [project-id id
          api-token (:authorization context)]
      (with-datasource-proxy result {:query query :project-id project-id :project-role "admin"
                                     :api-token api-token}
        ;; https://stackoverflow.com/questions/28091305/find-value-of-specific-key-in-nested-map
        ;; this is hack which assumes that only vectors will
        ;; contain entities in a response.
        (import-source
         (:request context)
         :datasource-query
         project-id {:query query :entities (->> (:body result)
                                                 (tree-seq map? vals)
                                                 (filter vector?)
                                                 flatten
                                                 (into []))})
        (resolve-as true)))))

;;;
;;; datasource
;;;

(defmethod make-source-meta :datasource
  [_ {:keys [datasource-id datasource-name]}]
  {:source "Datasource" :datasource-id datasource-id :datasource-name datasource-name})

(defmethod import-source :datasource
  [request _x project-id {:keys [datasource-id entities datasource-name]} & {:as options}]
  (if (seq (->> (source/project-sources project-id)
                (filter #(= (get-in % [:meta :datasource-id]) datasource-id))))
    (do (log/warnf "import-source %s - datasource-id %s already imported" _x datasource-id)
        {:error {:message (format "datasource-id %s already imported" datasource-id)}})
    (do (import-source-impl
         request project-id
         (source/make-source-meta _x {:datasource-id datasource-id
                                      :datasource-name datasource-name})
         {:types {:article-type "datasource" :article-subtype "entity"}
          :get-article-refs (constantly entities)
          :get-articles process-datasource-entities}
         options)
        {:result true})))

(def import-datasource-flattened
  ^ResolverResult
  (fn [context {:keys [id datasource]} _]
    (let [project-id id
          api-token (:authorization context)]
      (if (<= datasource 3)
        (fail "That datasource can't be imported, please select an id > 3")
        (with-datasource-proxy result {:query [[:datasource {:id datasource}
                                                [:name [:datasets
                                                        [:id :name [:entities [:id]]]]]]]
                                       :project-id project-id :project-role "admin"
                                       :api-token api-token}
          (let [{:keys [datasets name]} (get-in result [:body :data :datasource])
                entities (medley/join (map :entities datasets))]
            (try (import-source (:request context)
                                :datasource project-id {:datasource-id datasource
                                                        :datasource-name name
                                                        :entities entities})
                 (resolve-as true)
                 (catch Exception e
                   (fail (str "There was an exception with message: " (.getMessage e)))))))))))

;;;
;;; dataset
;;;

(defmethod make-source-meta :dataset
  [_ {:keys [dataset-id dataset-name]}]
  {:source "Dataset" :dataset-id dataset-id :dataset-name dataset-name})

(defmethod import-source :dataset
  [request _x project-id {:keys [dataset-id entities dataset-name]} & {:as options}]
  (if (seq (->> (source/project-sources project-id)
                (filter #(= (get-in % [:meta :dataset-id]) dataset-id))))
    (do (log/warnf "import-source %s - dataset-id %s already imported" _x dataset-id)
        {:error {:message (format "dataset-id %s already imported" dataset-id)}})
    (do (import-source-impl
         request project-id
         (source/make-source-meta _x {:dataset-id dataset-id :dataset-name dataset-name})
         {:types {:article-type "datasource" :article-subtype "entity"}
          :get-article-refs (constantly entities)
          :get-articles process-datasource-entities}
         options)
        {:result true})))

(def import-dataset
  ^ResolverResult 
  (fn [context {:keys [id dataset]} _]
    (let [project-id id
          api-token (:authorization context)]
      (if (<= dataset 7)
        (fail "That dataset can't be imported, please select an id > 7")
        (with-datasource-proxy result {:query [[:dataset {:id dataset}
                                                [:name [:entities [:id]]]]]
                                       :project-id project-id :project-role "admin"
                                       :api-token api-token}
          (let [{:keys [entities name]} (get-in result [:body :data :dataset])]
            (import-source (:request context) :dataset project-id
                           {:dataset-id dataset
                            :dataset-name name
                            :entities entities})
            (resolve-as true)))))))

(def import-datasource
  ^ResolverResult
  (fn [context {:keys [id datasource]} _]
    (let [project-id id
          api-token (:authorization context)]
      (if (<= datasource 3)
        (fail "That datasource can't be imported, please select an id > 3")
        (with-datasource-proxy result {:query [[:datasource {:id datasource}
                                                [[:datasets [:id :name [:entities [:id]]]]]]]
                                       :project-id project-id :project-role "admin"
                                       :api-token api-token}
          (let [datasets (get-in result [:body :data :datasource :datasets])]
            (try (doseq [{:keys [id name entities]} datasets]
                   (import-source (:request context) :dataset project-id
                                  {:dataset-id id
                                   :dataset-name name
                                   :entities entities}))
                 (resolve-as true)
                 (catch Exception e
                   (fail (str "There was an exception with message: " (.getMessage e)))))))))))


