(ns sysrev.web.routes.api.srvc
  (:require [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :as db]
            [sysrev.postgres.interface :as pg]
            [sysrev.web.routes.api.core :refer [def-webapi]]))

(declare label-schema)

(defn hash-process []
  (let [{:keys [in out] :as proc} (p/process ["sr" "hash"])]
    {:process proc
     :reader (io/reader out)
     :writer (io/writer in)}))

(defn add-hash [{:keys [line-seq reader writer]} event]
  (json/write event writer)
  (.newLine writer)
  (.flush writer)
  (-> reader .readLine (json/read-str :key-fn keyword)))

(defn get-articles [sr-context project-id]
  (->> (db/with-tx [sr-context sr-context]
         (db/execute!
          sr-context
          {:select :article-id
           :from :article
           :where [:= :project-id project-id]}))
       (map :article/article-id)
       ds-api/get-articles-content))

(defn get-project-labels [sr-context project-id]
  (db/with-tx [sr-context sr-context]
    (db/execute!
     sr-context
     {:select [:definition :label-id :name :question :required :value-type]
      :from :label
      :where [:= :project-id project-id]})))

(defn get-label-answers [sr-context label-id]
  (db/with-tx [sr-context sr-context]
    (db/execute!
     sr-context
     {:select [:answer :article-id :email :label-id :updated-time]
      :from :article-label
      :where [:= :label-id label-id]
      :join [:web-user [:= :article-label.user-id :web-user.user-id]]
      :order-by [:article-label.updated-time]})))

(defn convert-article [hasher article]
  (->> {:data (dissoc article :article-id :id :project-id)
        :type "document"}
       (add-hash hasher)))

(defn convert-label [hasher {:label/keys [definition name question required value-type]}]
  (let [{:keys [all-values inclusion-values]} definition
        label (cond-> {}
                (seq all-values) (assoc :categories all-values)
                (seq inclusion-values) (assoc :inclusion_values inclusion-values))]
    (->> {:data (assoc label
                       :id name
                       :question question
                       :required required)
          :type "label"}
         (add-hash hasher))))

(defn convert-label-answer [hasher articles-by-id labels-by-id
                            {:article-label/keys [answer article-id label-id updated-time]
                             :web-user/keys [email]}]
  (->> {:data {:answer answer
               :document (:hash (articles-by-id article-id))
               :label (:hash (labels-by-id label-id))
               :reviewer (str "mailto:" email)
               :timestamp (-> updated-time .getTime (quot 1000))}
        :type "label-answer"}
       (add-hash hasher)))

(def-webapi :srvc-events :get
  {:allow-public? true
   :required [:project-id]}
  (fn [{:keys [params sr-context]}]
    (let [hasher (hash-process)
          project-id (-> params :project-id parse-long)
          articles (get-articles sr-context project-id)
          articles-by-id (into {} (for [[article-id article] articles]
                                    [article-id (convert-article hasher article)]))
          labels (get-project-labels sr-context project-id)
          labels-by-id (into {} (for [label labels]
                                  [(:label/label-id label) (convert-label hasher label)]))
          answers (->> (mapcat #(get-label-answers sr-context (:label/label-id %)) labels)
                       (map #(convert-label-answer hasher articles-by-id labels-by-id %)))
          events (concat (vals labels-by-id) (vals articles-by-id) answers)]
      {:headers {"Content-Type" "application/ndjson"}
       :body (->> events
                  (map json/write-str)
                  (str/join "\n"))})))

(defn boolean-label-schema [{:keys [multi?]}]
  (let [spec {:type "boolean"}]
    (if multi?
      {:type "array" :items spec}
      spec)))

(defn categorical-label-schema [{:keys [all-values multi?]}]
  (let [spec {:enum all-values :type "string"}]
    (if multi?
      {:type "array" :items spec}
      spec)))

(defn group-label-schema [sr-context {:label/keys [definition label-id]}]
  (let [sublabels (db/with-tx [sr-context sr-context]
                    (db/execute!
                     sr-context
                     {:select :*
                      :from :label
                      :where [:in :root-label-id-local
                              {:select :label-id-local
                               :from :label
                               :where [:= :label-id label-id]}]}))
        props (->> sublabels
                   (mapv #(do [(:label/name %) (label-schema sr-context %)]))
                   (into {}))
        spec {:type "object"
              :properties props}]
    (if (:multi? definition)
      {:type "array" :items spec}
      spec)))

(defn string-label-schema [{:keys [max-length multi? regex]}]
  (let [spec (cond-> {:type "string"}
               max-length (assoc :maxLength max-length)
               (seq regex) (assoc :pattern (str "^" (first regex) "$")))]
    (if multi?
      {:type "array" :items spec}
      spec)))

(defn label-schema [sr-context {:label/keys [definition value-type] :as label}]
  (case value-type
    "boolean" (boolean-label-schema definition)
    "categorical" (categorical-label-schema definition)
    "group" (group-label-schema sr-context label)
    "string" (string-label-schema definition)))

(defn make-json-schema [sr-context hasher {:label/keys [value-type] :as label}]
  (case value-type
    "boolean"
    "https://raw.githubusercontent.com/insilica/rs-srvc/master/src/schema/label-answer/boolean-v1.json"

    ("annotation" "relationship") nil

    ("categorical" "group" "string")
    (let [schema (-> (label-schema sr-context label)
                     (assoc "$schema" "http://json-schema.org/draft-07/schema"))
          hash (:hash (add-hash hasher {:data schema :type "document"}))
          schema-url (str "https://sysrev.com/web-api/srvc-json-schema?hash=" hash)
          schema (assoc schema "$id" schema-url)]
      (db/with-tx [sr-context sr-context]
        (db/execute!
         sr-context
         {:insert-into :srvc-json-schema
          :values [{:hash hash :schema (pg/jsonb-pgobject schema)}]
          :on-conflict []
          :do-nothing []}))
      schema-url)))

(defn sryaml-label [sr-context hasher {:label/keys [definition name question required] :as label}]
  (let [json-schema-url (make-json-schema sr-context hasher label)
        {:keys [inclusion-values]} definition]
    (cond-> {:question question
             :required (boolean required)
             :type "json_schema"}
      (seq inclusion-values) (assoc :inclusion_values inclusion-values)
      json-schema-url (assoc :json_schema_uri json-schema-url))))

(def-webapi :srvc-config :get
  {:allow-public? true
   :required [:project-id]}
  (fn [{:keys [params sr-context]}]
    (let [hasher (hash-process)
          project-id (-> params :project-id parse-long)
          labels (get-project-labels sr-context project-id)]
      {:headers {"Content-Type" "application/yaml"}
       :body (yaml/generate-string
              {:flows {:label {:steps
                               [{:run_embedded "generator-file data/docs.jsonl"}
                                {:run_embedded "remove-reviewed"}
                                {:run_embedded "html src/resources/public/label.html"
                                  :labels (mapv :label/name labels)}]}}
               :labels (into {} (for [label labels]
                                  [(:label/name label) (sryaml-label sr-context hasher label)]))})})))

(defn get-schema [sr-context hash]
  (-> (db/with-tx [sr-context sr-context]
        (db/execute-one!
         sr-context
         {:select :schema
          :from :srvc-json-schema
          :where [:= :hash hash]}))
      :srvc-json-schema/schema))

(def-webapi :srvc-json-schema :get
  {:allow-public? true
   :required [:hash]}
  (fn [{:keys [params sr-context]}]
    (let [schema (get-schema sr-context (:hash params))]
      {:headers {"Content-Type" "application/json"}
       :body (json/write-str schema)})))
