(ns sysrev.api
  ^{:doc "An API for generating response maps that are common to /api/* and web-api/* endpoints"}
  (:require [bouncer.core :as b]
            [clojure.set :refer [rename-keys difference]]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [ring.util.response :as response]
            [sysrev.biosource.annotations :as annotations]
            [sysrev.biosource.importance :as importance]
            [sysrev.cache :refer [db-memo]]
            [sysrev.charts :as charts]
            [sysrev.config.core :refer [env]]
            [sysrev.db.articles :as articles]
            [sysrev.db.core :as db]
            [sysrev.db.files :as files]
            [sysrev.db.labels :as labels]
            [sysrev.db.plans :as plans]
            [sysrev.db.project :as project]
            [sysrev.db.sources :as sources]
            [sysrev.db.users :as users]
            [sysrev.files.s3store :as s3store]
            [sysrev.import.endnote :as endnote]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.stripe :as stripe]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.core :as sc]
            [sysrev.util :as util]
            [sysrev.shared.util :refer [map-values]]
            [sysrev.biosource.predict :as predict-api])
  (:import [java.io ByteArrayInputStream]))

(def default-plan "Basic")
;; Error code used
(def forbidden 403)
(def not-found 404)
(def internal-server-error 500)

(def max-import-articles (:max-import-articles env))

(defn create-project-for-user!
  "Create a new project for user-id using project-name and insert a minimum label, returning the project in a response map"
  [project-name user-id]
  (let [{:keys [project-id] :as project}
        (project/create-project project-name)]
    (labels/add-label-overall-include project-id)
    (project/add-project-note project-id {})
    (project/add-project-member project-id user-id
                                :permissions ["member" "admin"])
    {:result
     {:success true
      :project (select-keys project [:project-id :name])}}))

(s/fdef create-project-for-user!
        :args (s/cat :project-name ::sp/name
                     :user-id ::sc/user-id)
        :ret ::sp/project)

(defn delete-project!
  "Delete a project with project-id by user-id. Checks to ensure the user is an admin of that project. If there are reviewed articles in the project, disables project instead of deleting it"
  [project-id user-id]
  (assert (project/member-has-permission? project-id user-id "admin"))
  (if (project/project-has-labeled-articles? project-id)
    (do (project/disable-project! project-id)
        {:result {:success true
                  :project-id project-id}})
    (do (project/delete-project project-id)
        {:result {:success true
                  :project-id project-id}})))

(s/fdef delete-project!
        :args (s/cat :project-id int?
                     :user-id int?)
        :ret map?)

(defn import-articles-from-search
  "Import PMIDS resulting from using search-term as a query at source.
  Currently only support PubMed as a source for search queries. Will
  only allow a search-term to be used once for a project. i.e. You
  cannot have multiple 'foo bar' searches for one project over
  multiple dates, but you are allowed multiple search terms for a
  project e.g. 'foo bar' and 'baz qux'"
  [project-id search-term source & {:keys [threads] :or {threads 1}}]
  (let [project-sources (sources/project-sources project-id)
        search-term-sources (filter #(= (get-in % [:meta :search-term]) search-term) project-sources)
        pmids-count (:count (pubmed/get-search-query-response search-term 1)) ]
    (cond (> pmids-count max-import-articles)
          {:error {:status internal-server-error
                   :message (format "Too many PMIDs from search (max %d; got %d)"
                                    max-import-articles pmids-count)}}

          (not (project/project-exists? project-id))
          {:error {:status not-found
                   :message "Project does not exist"}}

          ;; there is no import going on for this search-term
          ;; execute it
          (and (empty? search-term-sources)
               (= source "PubMed"))
          (try
            (let [pmids (pubmed/get-all-pmids-for-query search-term)
                  meta (sources/import-pmids-search-term-meta
                        search-term (count pmids))
                  success?
                  (pubmed/import-pmids-to-project-with-meta!
                   pmids project-id meta
                   :use-future? true
                   :threads threads)]
              (if success?
                {:result {:success true}}
                {:error {:status internal-server-error
                         :message "Error during import (1)"}}))
            (catch Throwable e
              {:error {:status internal-server-error
                       :message "Error during import (2)"}}))

          (not (empty? search-term-sources))
          {:result {:success true}}

          :else
          {:error {:status internal-server-error
                   :message "Unknown event occurred"}})))

(s/def ::threads integer?)

(s/fdef import-articles-from-search
        :args (s/cat :project-id int?
                     :search-term string?
                     :source string?
                     :keys (s/keys* :opt-un [::threads]))
        :ret map?)

(defn import-articles-from-file
  "Import PMIDs into project-id from file. A file is a white-space/comma separated file of PMIDs. Only one import from a file is allowed at one time"
  [project-id file filename & {:keys [threads] :or {threads 1}}]
  (let [project-sources (sources/project-sources project-id)
        filename-sources (filter #(= (get-in % [:meta :filename]) filename)
                                 project-sources)]
    (try
      (let [pmid-vector (pubmed/parse-pmid-file file)]
        (cond (and (sequential? pmid-vector)
                   (> (count pmid-vector) max-import-articles))
              {:error {:status internal-server-error
                       :message (format "Too many PMIDs from file (max %d; got %d)"
                                        max-import-articles (count pmid-vector))}}

              (empty? pmid-vector)
              {:error {:status internal-server-error
                       :message "Error parsing file"}}

              (not (project/project-exists? project-id))
              {:error {:status not-found
                       :message "Project does not exist"}}

              ;; there is no import going on for this filename
              (and (empty? filename-sources))
              (try
                (let [meta (sources/import-pmids-from-filename-meta filename)
                      success?
                      (pubmed/import-pmids-to-project-with-meta!
                       pmid-vector project-id meta
                       :use-future? true
                       :threads threads)]
                  (if success?
                    {:result {:success true}}
                    {:error {:status internal-server-error
                             :message "Error during import (1)"}}))
                (catch Throwable e
                  {:error {:status internal-server-error
                           :message "Error during import (2)"}}))

              (not (empty? filename-sources))
              {:result {:success true}}

              :else
              {:error {:status forbidden
                       :message "Unknown event occurred"}}))
      (catch Throwable e
        {:error {:status internal-server-error
                 :message "Error parsing file"}}))))

(defn import-articles-from-endnote-file
  "Import PMIDs into project-id from file. A file is a white-space/comma separated file of PMIDs. Only one import from a file is allowed at one time"
  [project-id file filename & {:keys [threads] :or {threads 1}}]
  (let [project-sources (sources/project-sources project-id)
        filename-sources (filter #(= (get-in % [:meta :filename]) filename) project-sources)]
    (try
      (cond (not (project/project-exists? project-id))
            {:error {:status not-found
                     :message "Project does not exist"}}
            ;; there is no import going on for this filename
            (and (empty? filename-sources))
            (do
              (future (endnote/import-endnote-library!
                       file
                       filename
                       project-id
                       :use-future? true
                       :threads 3))
              {:result {:success true}})
            (not (empty? filename-sources))
            {:result {:success true}}
            :else
            {:error {:status internal-server-error
                     :message "Unknown event occurred"}})
      (catch Throwable e
        {:error {:status internal-server-error
                 :message (.getMessage e)}}))))

(defn project-sources
  "Return sources for project-id"
  [project-id]
  (if (project/project-exists? project-id)
    {:result {:success true
              :sources (sources/project-sources project-id)}}
    {:error {:status not-found
             :mesaage (str "project-id " project-id  " does not exist")}}))

(s/fdef project-sources
        :args (s/cat :project-id int?)
        :ret map?)

(defn delete-source!
  "Delete a source with source-id by user-id."
  [source-id]
  (cond (sources/source-has-labeled-articles? source-id)
        {:error {:status forbidden
                 :message "Source contains reviewed articles"}}
        (not (sources/source-exists? source-id))
        {:error {:status not-found
                 :message (str "source-id " source-id " does not exist")}}
        :else (let [project-id (sources/source-id->project-id source-id)]
                (sources/delete-project-source! source-id)
                (predict-api/schedule-predict-update project-id)
                (importance/schedule-important-terms-update project-id)
                {:result {:success true}})))

(s/fdef delete-source!
        :args (s/cat :source-id int?)
        :ret map?)

(defn toggle-source!
  "Toggle a source as being enabled or disabled."
  [source-id enabled?]
  (if (sources/source-exists? source-id)
    (let [project-id (sources/source-id->project-id source-id)]
      (sources/toggle-source! source-id enabled?)
      (predict-api/schedule-predict-update project-id)
      (importance/schedule-important-terms-update project-id)
      {:result {:success true}})
    {:error {:status not-found
             :message (str "source-id " source-id " does not exist")}}))

(s/fdef toggle-source!
        :args (s/cat :source-id int?
                     :enabled? boolean?)
        :ret map?)

(defn register-user!
  "Register a user and add them as a stripe customer"
  [email password project-id]
  (assert (string? email))
  (let [user (users/get-user-by-email email)
        db-result
        (when-not user
          (try
            (users/create-user email password :project-id project-id)
            true
            (catch Throwable e
              e)))]
    (cond
      user
      {:result
       {:success false
        :message "Account already exists for this email address"}}
      (isa? (type db-result) Throwable)
      {:error
       {:status 500
        :message "An error occurred while creating account"
        :exception db-result}}
      (true? db-result)
      (do
        ;; create-sysrev-stripe-customer! will handle
        ;; logging any error messages related to not
        ;; being able to create a stripe customer for the
        ;; user
        (users/create-sysrev-stripe-customer!
         (users/get-user-by-email email))
        ;; subscribe the customer to the basic plan, by default
        (stripe/subscribe-customer! (users/get-user-by-email email)
                                    default-plan)
        {:result
         {:success true}})
      :else (throw (util/should-never-happen-exception)))))

(defn add-payment-method
  "Using a stripe token, update the payment method for user"
  [user token]
  (let [stripe-response (stripe/update-customer-card!
                         user
                         token)]
    (if (:error stripe-response)
      stripe-response
      {:success true})))

(defn plans
  "Get available plans"
  []
  {:result {:success true
            :plans (->> (stripe/get-plans)
                        :data
                        (filter #(not= (:name %) "ProjectSupport"))
                        (mapv #(select-keys % [:name :amount :product])))}})

(defn get-current-plan
  "Get the plan for user-id"
  [user]
  {:result {:success true
            :plan (plans/get-current-plan user)}})

(defn subscribe-to-plan
  "Subscribe user to plan-name"
  [user plan-name]
  (let [stripe-response (stripe/subscribe-customer! user plan-name)]
    (if (:error stripe-response)
      (assoc stripe-response
             :error
             (merge (:error stripe-response)
                    {:status not-found}))
      stripe-response)))

(defn support-project
  "User supports project"
  [user project-id amount]
  (let [{:keys [quantity id]} (plans/user-current-project-support user project-id)
        minimum-support-level 100]
    (cond
      (and (not (nil? amount))
           (< amount minimum-support-level))
      {:error {:status forbidden
               :type "amount_too_low"
               :message {:minimum minimum-support-level}}}
      ;; user is not supporting this project
      (nil? quantity)
      (stripe/support-project! user project-id amount)
      ;; user is already supporting at this amount, do nothing
      (= quantity amount)
      {:error {:status forbidden
               :type "already_supported_at_amount"
               :message {:amount amount}}}
      ;; the user is supporting this project,
      ;; but not at this amount
      (not (nil? quantity))
      (do (stripe/cancel-subscription! id)
          (support-project user project-id amount))
      ;; something we hadn't planned for happened
      :else {:status internal-server-error
             :message "Unexpected outcome"})))

(defn current-project-support-level
  "The current level of support of this user for project-id"
  [user project-id]
  {:result (select-keys (plans/user-current-project-support user project-id) [:name :project-id :quantity])})

(defn user-support-subscriptions
  "The current support subscriptions for user"
  [user]
  {:result (mapv #(select-keys % [:name :project-id :quantity])
                 (plans/user-support-subscriptions user))})

(defn cancel-project-support
  "Cancel support for project-id by user"
  [user project-id]
  (let [{:keys [quantity id]} (plans/user-current-project-support user project-id)]
    (stripe/cancel-subscription! id)
    {:result {:success true}}))

(defn sync-labels
  "Given a map of labels, sync them with project-id."
  [project-id labels-map]
  ;; first let's convert the labels to a set
  (let [client-labels (set (vals labels-map))
        all-labels-valid? (fn [labels]
                            (every? true? (map #(b/valid? % (labels/label-validations %)) client-labels)))]
    ;; labels must be valid
    (if (all-labels-valid? client-labels)
      ;; labels are valid
      (let [server-labels (set (vals (project/project-labels project-id true)))
            ;; new labels are given a randomly generated string id on
            ;; the client, so labels that are non-existent on the server
            ;; will have string as opposed to UUID label-ids
            new-client-labels (set (filter #(= java.lang.String
                                               (type (:label-id %)))
                                           client-labels))
            current-client-labels (set (filter #(= java.util.UUID
                                                   (type (:label-id %)))
                                               client-labels))
            modified-client-labels (difference current-client-labels server-labels)]
        ;; creation/modification of labels should be done
        ;; on labels that have been validated.
        ;;
        ;; labels are never deleted, the enabled flag is set to 'empty'
        ;; instead
        ;;
        ;; If there are issues with a label being incorrectly
        ;; modified, add a validator for that case so that
        ;; the error can easily be reported in the client
        (when-not (empty? new-client-labels)
          (doall (map (partial labels/add-label-entry project-id)
                      new-client-labels)))
        (when-not (empty? modified-client-labels)
          (doall (map #(labels/alter-label-entry project-id
                                                 (:label-id %) %)
                      modified-client-labels)))
        {:result {:valid? true
                  :labels (project/project-labels project-id true)}})
      ;; labels are invalid
      {:result {:valid? false
                :labels
                (->> client-labels
                     ;; validate each label
                     (map #(b/validate % (labels/label-validations %)))
                     ;; get the label map with attached errors
                     (map second)
                     ;; rename bouncer.core/errors -> errors
                     (map #(rename-keys % {:bouncer.core/errors :errors}))
                     ;; create a new hash map of labels which include
                     ;; errors
                     (map #(hash-map (:label-id %) %))
                     ;; finally, return a map
                     (apply merge))}})))

(defn important-terms
  "Given a project-id, return the term counts for the top n most used terms"
  [project-id & [n]]
  (let [n (or n 20)
        terms (importance/project-important-terms project-id)]
    {:result
     {:terms
      (->> terms (map-values
                  #(->> %
                        (sort-by :instance-count >)
                        (take n)
                        (into []))))
      :loading
      (importance/project-importance-loading? project-id)}}))

(defn prediction-histogram
  "Given a project-id, return a vector of {:count <int> :score <float>}"
  [project-id]
  (db/with-project-cache
    project-id [:prediction-histogram]
    (let [all-score-vals (->> (range 0 1 0.02) (mapv #(util/truncate-to 0.02 2 %)))
          prediction-scores (->> (articles/project-prediction-scores project-id)
                                 (mapv #(assoc % :rounded-score (->> (:val %) (util/truncate-to 0.02 2)))))
          predictions-map (zipmap (mapv :article-id prediction-scores)
                                  (mapv :rounded-score prediction-scores))
          project-article-statuses (labels/project-article-statuses project-id)
          reviewed-articles-no-conflicts (->> project-article-statuses
                                              (group-by :group-status)
                                              ((fn [reviewed-articles]
                                                 (concat
                                                  (:consistent reviewed-articles)
                                                  (:single reviewed-articles)
                                                  (:resolved reviewed-articles)))))
          unreviewed-articles (let [all-article-ids (set (mapv :article-id prediction-scores))
                                    reviewed-article-ids (set (mapv :article-id project-article-statuses))]
                                (clojure.set/difference all-article-ids reviewed-article-ids))
          get-rounded-score-fn (fn [article-id]
                                 (get predictions-map article-id))
          reviewed-articles-scores (mapv #(assoc % :rounded-score
                                                 (get-rounded-score-fn (:article-id %)))
                                         reviewed-articles-no-conflicts)
          unreviewed-articles-scores (mapv #(hash-map :rounded-score
                                                      (get-rounded-score-fn %))
                                           unreviewed-articles)
          histogram-fn (fn [scores]
                         (let [score-counts (->> scores
                                                 (group-by :rounded-score)
                                                 (map-values count))]
                           (->> all-score-vals
                                (mapv (fn [score]
                                        {:score score
                                         :count (get score-counts score 0)}))
                                ;; trim empty sequences at start and end
                                (drop-while #(= 0 (:count %)))
                                reverse
                                (drop-while #(= 0 (:count %)))
                                reverse
                                vec)))]
      (if-not (empty? prediction-scores)
        {:result
         {:prediction-histograms
          {:reviewed-include-histogram
           (histogram-fn (filterv #(true? (:answer %))
                                  reviewed-articles-scores))
           :reviewed-exclude-histogram
           (histogram-fn (filterv #(false? (:answer %))
                                  reviewed-articles-scores))
           :unreviewed-histogram
           (histogram-fn unreviewed-articles-scores)}}}
        {:result
         {:prediction-histograms
          {:reviewed-include-histogram []
           :reviewed-exclude-histogram []
           :unreviewed-histogram []}}}))))

(def annotations-atom (atom {}))

(defn annotations-by-hash!
  "Returns the annotations by hash (.hashCode <string>). Assumes
  annotations-atom has already been set by a previous fn"
  [hash]
  (let [annotations (annotations/get-annotations
                     (get @annotations-atom hash))]
    ;; return the annotations
    annotations))

(def db-annotations-by-hash!
  (db-memo db/active-db annotations-by-hash!))

;; note: this could possibly have a thread safety issue
(defn annotations-wrapper!
  "Returns the annotations for string using a hash wrapper"
  [string]
  (let [hash (util/string->md5-hash (if (string? string)
                         string
                         (pr-str string)))
        _ (swap! annotations-atom assoc hash string)
        annotations (db-annotations-by-hash! hash)]
    (swap! annotations-atom dissoc hash)
    annotations))

(defn article-abstract-annotations
  "Given an article-id, return a vector of annotation maps for that
  articles abstract"
  [article-id]
  {:result {:annotations (-> article-id
                             articles/query-article-by-id-full
                             :abstract
                             annotations-wrapper!)}})

(defn label-count-data
  "Given a project-id, return data for the label counts chart"
  [project-id]
  {:result {:data (charts/process-label-counts project-id)}})

(defn open-access-pdf
  [article-id]
  (if-let [filename (-> article-id
                        articles/article-pmcid
                        pubmed/article-pdf)]
    ;; there was a file
    (let [file (java.io.File. filename)
          bytes (util/slurp-bytes file)]
      (fs/delete filename)
      {:headers {"Content-Type" "application/pdf"}
       :body (java.io.ByteArrayInputStream. bytes)})
    {:headers {"Content-Type" "application/pdf"}
     :body nil}))

(defn open-access-available?
  [article-id]
  {:result {:available? (not (nil? (-> article-id
                                       articles/article-pmcid
                                       pubmed/article-pdf)))}})

(defn save-article-pdf
  "Handle saving a file on S3 and the associated accounting with it"
  [article-id file filename]
  (let [hash (util/file->sha-1-hash file)
        s3-id (files/id-for-s3-filename-key-pair
               filename hash)
        article-s3-association (files/get-article-s3-association
                                s3-id
                                article-id)]
    (cond
      ;; there is a file and it is already associated with this article
      (not (nil? article-s3-association))
      {:result {:success true
                :key hash}}
      ;; there is a file, but it is not associated with this article
      (not (nil? s3-id))
      (try (do (files/associate-s3-with-article s3-id
                                                article-id)
               {:result {:success true
                         :key hash}})
           (catch Throwable e
             {:error {:status internal-server-error
                      :message (.getMessage e)}}))
      ;; there is a file. but not with this filename
      (and (nil? s3-id)
           (files/s3-has-key? hash))
      (try
        (let [ ;; create the association between this file name and
              ;; the hash
              _ (files/insert-file-hash-s3-record filename hash)
              ;; get the new association's id
              s3-id (files/id-for-s3-filename-key-pair filename hash)]
          (files/associate-s3-with-article s3-id
                                           article-id)
          {:result {:success true
                    :key hash}})
        (catch Throwable e
          {:error {:status internal-server-error
                   :message (.getMessage e)}}))
      ;; the file does not exist in our s3 store
      (and (nil? s3-id)
           (not (files/s3-has-key? hash)))
      (try
        (let [ ;; create a new file on the s3 store
              _ (s3store/save-file file)
              ;; create a new association between this file name
              ;; and the hash
              _ (files/insert-file-hash-s3-record filename hash)
              ;; get the new association's id
              s3-id (files/id-for-s3-filename-key-pair filename hash)]
          (files/associate-s3-with-article s3-id article-id)
          {:result {:success true
                    :key hash}})
        (catch Throwable e
          {:error {:status internal-server-error
                   :message (.getMessage e)}}))
      :else {:error {:status internal-server-error
                     :message "Unknown Processing Error Occurred."}})))

(defn article-pdfs
  "Given an article-id, return a vector of maps that correspond to the files associated with article-id"
  [article-id]
  {:result {:success true
            :files (files/get-article-file-maps article-id)}})

(defn dissociate-pdf-article
  "Given an article-id, file key and filename remove the association between it and this article"
  [article-id key filename]
  (try (do (files/dissociate-file-from-article article-id key filename)
           {:result {:success true}})
       (catch Throwable e
         {:error internal-server-error
          :message (.getMessage e)})))

(defn get-s3-file
  "Given a key, return a file response"
  [key]
  (try
    (response/response (ByteArrayInputStream. (s3store/get-file key)))
    (catch Throwable e
      {:error internal-server-error
       :message (.getMessage e)})))

(defn view-s3-pdf
  [key]
  (try
    {:headers {"Content-Type" "application/pdf"}
     :body (java.io.ByteArrayInputStream. (s3store/get-file key))}
    (catch Throwable e
      {:error internal-server-error
       :message (.getMessage e)})))

(defn test-response
  "Server Sanity Check"
  []
  {:test "passing"})
