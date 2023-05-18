(ns sysrev.web.routes.api.srvc
  (:require [babashka.process :as p]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [medley.core :as medley]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :as db]
            [sysrev.label.answer :as answer]
            [sysrev.postgres.interface :as pg]
            [sysrev.project.core :as project]
            [sysrev.user.interface :as user]
            [sysrev.util :as util]
            [sysrev.web.routes.api.core :as web-api :refer [def-webapi]]))

(declare label-schema)

(defn hash-process []
  (let [{:keys [in out] :as proc} (p/process "sr" "hash")]
    {:process proc
     :reader (io/reader out)
     :writer (io/writer in)}))

(defn add-hash [{:keys [line-seq reader writer]} event]
  (json/write event writer)
  (.newLine writer)
  (.flush writer)
  (-> reader .readLine (json/read-str :key-fn keyword)))

(defn project-permissions-for-user [sr-context project-id user-id]
  (db/with-tx [sr-context sr-context]
    (some->
     sr-context
     (db/execute-one!
      {:select :permissions
       :from :project-member
       :where [:and
               :enabled
               [:= :project-id project-id]
               [:= :user-id user-id]]})
     :project-member/permissions .getArray set)))

(defn project-admin? [perms]
  (boolean
   (when perms
     (or (perms "admin") (perms "owner")))))

(defn project-member? [perms]
  (boolean
   (when perms
     (or (perms "member") (perms "admin") (perms "owner")))))

(defn blind-reviews? [sr-context project-id]
  (-> sr-context
      (db/execute-one!
       {:select [[[:cast [(keyword "->>") :settings "blind-reviewers"] :boolean] :blind]]
        :from :project
        :where [:= :project-id project-id]})
      :blind
      boolean))

(defn public-project? [sr-context project-id]
  (-> sr-context
      (db/execute-one!
       {:select [[[:cast [(keyword "->>") :settings "public-access"] :boolean] :public]]
        :from :project
        :where [:= :project-id project-id]})
      :public
      boolean))

(defn get-articles [sr-context project-id]
  (->> (db/with-tx [sr-context sr-context]
         (db/execute!
          sr-context
          {:select :article-id
           :from :article
           :where [:= :project-id project-id]}))
       (map :article/article-id)
       ds-api/get-articles-content))

(defn get-project-root-labels [sr-context project-id]
  (db/with-tx [sr-context sr-context]
    (db/execute!
     sr-context
     {:select [:definition :label-id :name :question :required :short-label :value-type]
      :from :label
      :where [:and
              [:= :enabled true]
              [:= :project-id project-id]
              [:= :root-label-id-local nil]]
      :order-by [:project-ordering]})))

(defn get-label-answers [sr-context project-id label-id user-id & {:keys [dev-key?]}]
  (db/with-tx [sr-context sr-context]
      (db/execute!
       sr-context
       {:select [:answer :article-id :email :label-id :updated-time]
        :from :article-label
        :where [:and
                [:= :label-id label-id]
                (when-not (or dev-key?
                              (not (blind-reviews? sr-context project-id))
                              (project-admin? (project-permissions-for-user sr-context project-id user-id)))
                  [:= :web-user.user-id user-id])]
        :join [:web-user [:= :article-label.user-id :web-user.user-id]]
        :order-by [:article-label.updated-time]})))

(defn add-datapub-data [sr-context article]
  (let [{:keys [entity-id]} (:datapub article)]
    (if-not entity-id
      article
      (let [{:keys [contentUrl mediaType metadata]}
            (dpc/get-dataset-entity
             entity-id "contentUrl mediaType metadata"
             :endpoint (get-in sr-context [:config :graphql-endpoint]))
            {:keys [body status] :as response} (when (= "application/json" mediaType)
                                                 (http/get contentUrl {:as :json}))]
        (when (and (not= 200 status) (= "application/json" mediaType))
          (throw (ex-info (str "Unexpected " status " response")
                          {:response response})))
        (cond-> article
          (seq body) (assoc :content body)
          (seq metadata) (assoc :metadata (json/read-str metadata))
          true (dissoc :article-subtype :article-type :datapub :dataset-id :external-id :types))))))

(defn add-sysrev-article-uri [sr-context {:keys [article-id project-id uri] :as article}]
  (if uri
    article
    (->> (str (util/server-url sr-context) "/p/" project-id "/article/" article-id)
         (assoc article :uri))))

(defn convert-article [sr-context hasher article]
  (let [{:keys [primary-title title uri] :as article} (add-sysrev-article-uri sr-context article)
        title (or title primary-title)
        data (-> (add-datapub-data sr-context article)
                 (dissoc :article-id :id :primary-title :project-id :uri)
                 (assoc :title title))
        {:keys [abstract content]} data
        abstract (or abstract
                     ;; ctgov
                     (-> content :ProtocolSection :DescriptionModule :DetailedDescription)
                     (-> content :ProtocolSection :DescriptionModule :BriefSummary))
        data (if abstract (assoc data :abstract abstract) data)]
    (->> {:data data :type "document" :uri uri}
         (add-hash hasher))))

(defn get-existing-documents [sr-context project-id]
  (->> (db/execute!
        sr-context
        {:select [:sd.data :sd.extra :sd.hash :sd.uri]
         :from [[:srvc-document :sd]]
         :join [[:srvc-document-to-project :sdtp] [:= :sd.hash :sdtp.hash]]
         :where [:= :sdtp.project-id project-id]})
       (map
        (fn [{:srvc-document/keys [data extra hash uri]}]
          (assoc extra
                 :data data
                 :hash hash
                 :type "document"
                 :uri uri)))))

(defn convert-label [hasher {:label/keys [definition name question required value-type]}]
  (let [{:keys [all-values inclusion-values]} definition
        label (cond-> {}
                (seq all-values) (assoc :categories all-values)
                (seq inclusion-values) (assoc :inclusion-values inclusion-values))]
    (->> {:data (assoc label
                       :id name
                       :question question
                       :required required)
          :type "label"}
         (add-hash hasher))))

(defn get-existing-labels [sr-context project-id]
  (->> (db/execute!
        sr-context
        {:select [:sl.data :sl.extra :sl.hash :sl.uri]
         :from [[:srvc-label :sl]]
         :join [[:srvc-label-to-project :sltp] [:= :sl.hash :sltp.hash]]
         :where [:= :sltp.project-id project-id]})
       (map
        (fn [{:srvc-label/keys [data extra hash uri]}]
          (assoc extra
                 :data data
                 :hash hash
                 :type "label"
                 :uri uri)))))

(defn convert-label-answer [hasher articles-by-id labels-by-id
                            {:article-label/keys [answer article-id label-id updated-time]
                             :web-user/keys [email]}]
  (->> {:data {:answer answer
               :event (:hash (articles-by-id article-id))
               :label (:hash (labels-by-id label-id))
               :reviewer (str "mailto:" email)
               :timestamp (-> updated-time .getTime (quot 1000))}
        :type "label-answer"}
       (add-hash hasher)))

(defn get-existing-label-answers [sr-context project-id]
  (->> (db/execute!
        sr-context
        {:select [:sla.answer :sla.event :sla.extra :sla.extra-data :sla.hash
                  :sla.label :sla.reviewer :sla.timestamp :sla.uri]
         :from [[:srvc-label-answer :sla]]
         :join [[:srvc-label-answer-to-project :slatp] [:= :sla.hash :slatp.hash]]
         :where [:= :slatp.project-id project-id]})
       (map
        (fn [{:srvc-label-answer/keys [answer event extra extra-data hash label reviewer timestamp uri]}]
          (assoc extra
                 :data (assoc extra-data
                              :answer answer
                              :event event
                              :label label
                              :reviewer reviewer
                              :timestamp timestamp)
                 :hash hash
                 :type "label-answer"
                 :uri uri)))))

(defn srvc-events [{:keys [params sr-context] :as request}]
  (let [hasher (hash-process)
        project-id (-> params :project-id parse-long)
        articles (get-articles sr-context project-id)
        articles-by-id (into {} (for [[article-id article] articles]
                                  [article-id (convert-article sr-context hasher article)]))
        labels (get-project-root-labels sr-context project-id)
        labels-by-id (into {} (for [label labels]
                                [(:label/label-id label) (convert-label hasher label)]))
        api-token (web-api/get-api-token request)
        user-id (some-> api-token user/user-by-api-token :user-id)
        dev-key? (util/sysrev-dev-key? sr-context api-token)
        answers (->> (mapcat #(get-label-answers sr-context
                                                 project-id
                                                 (:label/label-id %)
                                                 user-id
                                                 :dev-key? dev-key?)
                             labels)
                     (map #(convert-label-answer hasher articles-by-id labels-by-id %)))
        events (->> (concat (vals labels-by-id) (vals articles-by-id) answers
                            (get-existing-documents sr-context project-id)
                            (get-existing-labels sr-context project-id)
                            (get-existing-label-answers sr-context project-id))
                    (medley/distinct-by :hash)
                    (map #(medley/filter-vals (complement nil?) %)))]
    (cond
      (not (project/project-exists? project-id))
      {:status 404}

      (not (or dev-key?
               (public-project? sr-context project-id)
               (some->> user-id (project-permissions-for-user sr-context project-id) project-member?)))
      {:status 401}

      :else
      {:headers {"Content-Type" "application/ndjson"}
       :body (-> (map json/write-str events)
                 (interleave (repeat "\n")))})))

(def-webapi :api-srvc-events :get
  {:allow-public? true
   :path "/web-api/srvc-project/:project-id/api/v1/events"
   :required [:project-id]}
  srvc-events)

;; Deprecated endpoint
(def-webapi :srvc-events :get
  {:allow-public? true
   :required [:project-id]}
  srvc-events)

(defn multify [{:label/keys [question]
                {:keys [multi?]} :label/definition}
               spec
               & [extra]]
  (if multi?
    (merge
     {:items spec
      :title question
      :type "array"}
     extra)
    (assoc spec :title question)))

(defmulti label-schema
  (fn [_sr-context {:label/keys [value-type]}] value-type))

(defmethod label-schema "bolean" [_ label]
  (multify label {:type "boolean"}))

(defmethod label-schema "categorical" [_ {:label/keys [required]
                                          {:keys [all-values]} :label/definition
                                          :as label}]
  (multify
   label
   {:enum all-values
    :type "string"}
   (when required
     {:minItems 1})))

(defmethod label-schema "group" [sr-context {:label/keys [definition label-id]
                                             :as label}]
  (let [sublabels (db/with-tx [sr-context sr-context]
                    (db/execute!
                     sr-context
                     {:select :*
                      :from :label
                      :where [:and
                              [:= :enabled true]
                              [:in :root-label-id-local
                               {:select :label-id-local
                                :from :label
                                :where [:= :label-id label-id]}]]
                      :order-by [:project-ordering]}))
        props (reduce
               #(assoc % (:label/name %2) (label-schema sr-context %2))
               {}
               sublabels)
        required (filter :label/required sublabels)
        spec (cond-> {:properties props
                      :srvcOrder (mapv :label/name sublabels)
                      :type "object"}
               (seq required) (assoc :required (mapv :label/name required)))]
    (multify label spec)))

(defmethod label-schema "string" [_ {:label/keys [required]
                                     {:keys [max-length regex]} :label/definition
                                     :as label}]
  (let [spec (cond-> {:type "string"}
               max-length (assoc :maxLength max-length)
               required (assoc :minLength 1)
               (seq regex) (assoc :pattern (str "^" (first regex) "$")))]
    (multify label spec)))

(defn make-json-schema [sr-context hasher {:label/keys [value-type] :as label}]
  (case value-type
    "boolean" {:json-schema "boolean"}

    ("annotation" "relationship") nil

    ("categorical" "group" "string")
    (let [schema (-> (label-schema sr-context label)
                     (assoc "$schema" "http://json-schema.org/draft-07/schema"))
          hash (:hash (add-hash hasher {:data schema :type "document"}))
          schema-url (str (util/server-url sr-context) "/web-api/srvc-json-schema?hash=" hash)
          schema (assoc schema "$id" schema-url)]
      (db/with-tx [sr-context sr-context]
        (db/execute!
         sr-context
         {:insert-into :srvc-json-schema
          :values [{:hash hash :schema (pg/jsonb-pgobject schema)}]
          :on-conflict []
          :do-nothing []}))
      {:json-schema-uri schema-url})))

(defn sryaml-label [sr-context hasher {:label/keys [definition name question required short-label value-type] :as label}]
  (let [json-schema (make-json-schema sr-context hasher label)
        {:keys [inclusion-values]} definition]
    (cond-> {:question (if (= "group" value-type) short-label question)
             :required (boolean required)}
      (seq inclusion-values) (assoc :inclusion-values inclusion-values)
      true (merge json-schema))))

(def-webapi :srvc-config :get
  {:allow-public? true
   :required [:project-id]}
  (fn [{:keys [params sr-context] :as request}]
    (let [hasher (hash-process)
          project-id (-> params :project-id parse-long)
          labels (get-project-root-labels sr-context project-id)
          events-url (str (util/server-url sr-context) "/web-api/srvc-project/" project-id "/api/v1/events")
          api-token (web-api/get-api-token request)
          user (some-> api-token user/user-by-api-token)
          {:keys [user-id]} user]
      (cond
        (not (project/project-exists? project-id))
        {:status 404}

        (not (or (sysrev.util/sysrev-dev-key? sr-context api-token)
                 (public-project? sr-context project-id)
                 (some->> user-id (project-permissions-for-user sr-context project-id) project-member?)))
        {:status 401}

        :else
        {:headers {"Content-Type" "application/json"}
         :body (json/write-str
                {:db (str (util/server-url sr-context) "/web-api/srvc-project/" project-id)
                 :flows {:label {:steps
                                 [{:run-embedded (str "generator " events-url)}
                                  {:run-embedded "label-web"
                                   :labels (mapv :label/name labels)}]}}
                 :labels (into {} (for [label labels]
                                    [(:label/name label) (sryaml-label sr-context hasher label)]))
                 :reviewer (str "mailto:" (:email user))
                 :sources [{:uri events-url}]})}))))

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

(def re-ctgov #"^https:\/\/clinicaltrials\.gov\/ct2\/show\/(\w+)\/?$")
(def re-pubmed #"^https:\/\/pubmed\.ncbi\.nlm\.nih\.gov\/(\d+)\/?$")
(def re-sysrev #"\/p\/(\d+)\/article\/(\d+)\/?")

(defn uri->article-id [sr-context project-id uri]
  (let [[_ external-id] (or (re-find re-ctgov uri)
                            (re-find re-pubmed uri))]
    (if external-id
      (db/with-tx [sr-context sr-context]
        (-> (db/execute-one!
             sr-context
             {:select :article-id
              :from :article
              :join [:article-data [:= :article.article-data-id :article-data.article-data-id]]
              :where [:and
                      [:= :external-id [:cast (pr-str external-id) :jsonb]]
                      [:= :project-id project-id]
                      [:= :enabled true]]
              :order-by [:article-id]})
            :article/article-id))
      (some-> (re-find re-sysrev uri) (nth 2) parse-long))))

(defn hash->article-id [sr-context project-id hash]
  (db/with-tx [sr-context sr-context]
    (some->>
     (db/execute-one!
      sr-context
      {:select :uri
       :from :srvc-document
       :where [:= :hash hash]})
     :srvc-document/uri
     (uri->article-id sr-context project-id))))

(defn srvc-label-id->sysrev-label [sr-context project-id srvc-label-id]
  (db/with-tx [sr-context sr-context]
    (->
     (db/execute-one!
      sr-context
      {:select :*
       :from :label
       :where [:and
               [:= :enabled true]
               [:= :name srvc-label-id]
               [:= :project-id project-id]
               [:= :root-label-id-local nil]]}))))

(defn hash->sysrev-label [sr-context project-id hash]
  (db/with-tx [sr-context sr-context]
    (some->>
     (db/execute-one!
      sr-context
      {:select [[[:raw "data->>'id'"]]]
       :from :srvc-label
       :where [:= :hash hash]})
     first
     val
     (srvc-label-id->sysrev-label sr-context project-id))))

(defmulti sink-event
  (fn [_request _project-id event]
    (:type event)))

(defmethod sink-event :default [_ _ {:keys [type] :as event}]
  (throw (ex-info (str "Unknown event type: " type)
                  {:event event})))

(defmethod sink-event "document"
  [{:keys [sr-context]} project-id {:keys [data hash uri] :as event}]
  (db/with-tx [sr-context sr-context]
    (db/execute-one!
     sr-context
     {:insert-into :srvc-document
      :values [{:data [:lift data]
                :extra [:lift (dissoc event :data :hash :type :uri)]
                :hash hash
                :uri uri}]
      :on-conflict :hash
      :do-nothing []})
    (db/execute-one!
     sr-context
     {:insert-into :srvc-document-to-project
      :values [{:hash hash
                :project-id project-id}]
      :on-conflict [:hash :project-id]
      :do-nothing []})))

(defmethod sink-event "label"
  [{:keys [sr-context]} project-id {:keys [data hash uri] :as event}]
  (db/with-tx [sr-context sr-context]
    (db/execute-one!
     sr-context
     {:insert-into :srvc-label
      :values [{:data [:lift data]
                :extra [:lift (dissoc event :data :hash :type :uri)]
                :hash hash
                :uri uri}]
      :on-conflict :hash
      :do-nothing []})
    (db/execute-one!
     sr-context
     {:insert-into :srvc-label-to-project
      :values [{:hash hash
                :project-id project-id}]
      :on-conflict [:hash :project-id]
      :do-nothing []})))

(defn group-label-names-map [sr-context group-label-id-local]
  (db/with-tx [sr-context sr-context]
    (-> sr-context
        (db/execute!
         {:select [:name :label-id]
          :from :label
          :where [:= :root-label-id-local group-label-id-local]})
        (->> (reduce
              (fn [m {:label/keys [label-id name]}]
                (assoc m (keyword name) label-id))
              {})))))

(defn srvc-answer->sysrev [sr-context project-id label-hash answer]
  (db/with-tx [sr-context sr-context]
    (let [{:label/keys [label-id label-id-local value-type]} (hash->sysrev-label sr-context project-id label-hash)]
      (if (not= "group" value-type)
        {label-id answer}
        (let [names-map (group-label-names-map sr-context label-id-local)
              values (->> answer
                          (map
                           (fn [m]
                             (medley/map-keys names-map m)))
                          (map-indexed
                           (fn [i v]
                             [(str i) v]))
                          (into {}))]
          {label-id {:labels values}})))))

(defn sync-label-answer [{:keys [sr-context] :as request} project-id {{:keys [answer event label reviewer]} :data}]
  (let [api-token (web-api/get-api-token request)
        dev-key? (util/sysrev-dev-key? sr-context api-token)
        {:keys [email user-id]} (when-not dev-key? (user/user-by-api-token api-token))
        permissions (some->> user-id (project-permissions-for-user sr-context project-id))
        article-id (hash->article-id sr-context project-id event)]
    (cond
      (or dev-key? (project-admin? permissions)) nil

      (not= reviewer (str "mailto:" email))
      (throw (ex-info "Reviewer does not match user email"
                      {:email email
                       :reviewer reviewer}))

      article-id
      (answer/set-user-article-labels
       user-id
       article-id
       (srvc-answer->sysrev sr-context project-id label answer)
       {:confirm? true}))))

(defmethod sink-event "label-answer"
  [{:keys [sr-context] :as request} project-id
   {:keys [data hash uri] :as la
    {:keys [answer event label reviewer timestamp]} :data}]
  (db/with-tx [sr-context sr-context]
    (db/execute-one!
     sr-context
     {:insert-into :srvc-label-answer
      :values [{:answer [:cast (json/write-str answer) :jsonb]
                :event event
                :extra [:lift (dissoc la :data :hash :type :uri)]
                :extra-data [:lift (dissoc data :answer :event :label :reviewer :timestamp)]
                :hash hash
                :label label
                :reviewer reviewer
                :timestamp timestamp
                :uri uri}]
      :on-conflict :hash
      :do-nothing []})
    (let [{:next.jdbc/keys [update-count]}
          (db/execute-one!
           sr-context
           {:insert-into :srvc-label-answer-to-project
            :values [{:hash hash
                      :project-id project-id}]
            :on-conflict [:hash :project-id]
            :do-nothing []})]
      (when (= 1 update-count)
        (sync-label-answer request project-id la)))))

(def-webapi :srvc-upload :post
  {:allow-public? false
   :path "/web-api/srvc-project/:project-id/api/v1/upload"
   :required [:project-id]}
  (fn [{:keys [body params sr-context] :as request}]
    (let [project-id (some-> params :project-id parse-long)
          api-token (web-api/get-api-token request)
          user-id (some-> api-token user/user-by-api-token :user-id)]
      (cond
        (not (project/project-exists? project-id))
        {:status 404
         :body {:error {:message "Project does not exist"}}}

        (not (or (some->> user-id (project-permissions-for-user sr-context project-id) project-member?)
                 (util/sysrev-dev-key? sr-context api-token)))
        {:status 403
         :body {:error {:message "Forbidden"}}}

        :else
        (do
          (sink-event request project-id body)
          {:status 201})))))

nil
