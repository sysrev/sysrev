(ns sysrev.source.files
  (:require babashka.fs
            [clojure.data.xml :as dxml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [remvee.base64 :as base64]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.datapub-client.interface.queries :as dpcq]
            [sysrev.db.core :as db]
            [sysrev.file.core :as file]
            [sysrev.file.s3 :as s3]
            [sysrev.formats.endnote :as endnote]
            [sysrev.json.interface :as json]
            [sysrev.postgres.interface :as pg]
            [sysrev.ris.interface :as ris]
            [sysrev.source.core :as source]
            [sysrev.source.files :as files]
            [sysrev.util :as util]
            [sysrev.util-lite.interface :as ul]))

(defn insert-job!
  [context type payload & {:keys [started-at status] :or {status "new"}}]
  (->> {:insert-into :job
        :returning [:id]
        :values [{:payload (pg/jsonb-pgobject payload)
                  :started-at started-at
                  :status status
                  :type type}]}
       (db/execute-one! context)))

(defn create-source! [sr-context project-id dataset-id files]
  (->> {:insert-into :project-source
        :values [{:dataset-id dataset-id
                  :import-date :%now
                  :meta (pg/jsonb-pgobject
                         {:files (mapv #(select-keys % [:content-type :filename :s3-key]) files)
                          :importing-articles? true})
                  :project-id project-id}]
        :returning :*}
       (db/execute-one! sr-context)))

(defn get-article-data! [sr-context entity-id]
  (->> {:select :*
        :from :article-data
        :where [:and
                [:= :datasource-name "datapub"]
                [:= :external-id (pg/jsonb-pgobject entity-id)]]}
       (db/execute-one! sr-context)))

(defn create-article-data! [sr-context {:keys [content dataset-id entity-id title]}]
  {:pre [(map? sr-context) (string? entity-id) (string? title)]}
  (->> {:insert-into :article-data
        :returning :*
        :values [{:article-subtype "entity"
                  :article-type "datapub"
                  :content (pg/jsonb-pgobject content)
                  :dataset-id dataset-id
                  :datasource-name "datapub"
                  :external-id (pg/jsonb-pgobject entity-id)
                  :title title}]}
       (db/execute-one! sr-context)))

(defn goc-article-data!
  "Get or create an article-data row for an entity."
  [sr-context {:keys [entity-id] :as m}]
  (db/with-long-tx [sr-context sr-context]
    (or (get-article-data! sr-context entity-id)
        (create-article-data! sr-context m))))

(defn create-article!
  [sr-context project-id source-id article-data-id]
  (db/with-long-tx [sr-context sr-context]
    (when-let [article-id (or (->> {:select :article-id
                                    :from :article
                                    :where [:and
                                            [:= :article-data-id article-data-id]
                                            [:= :project-id project-id]]}
                                   (db/execute-one! sr-context)
                                   :article/article-id)
                              (->> {:insert-into :article
                                    :returning :article-id
                                    :values [{:article-data-id article-data-id
                                              :project-id project-id}]}
                                   (db/execute-one! sr-context)
                                   :article/article-id))]
      (->> {:insert-into :article-source
            :values [{:article-id article-id :source-id source-id}]
            :on-conflict []
            :do-nothing []}
           (db/execute-one! sr-context))
      nil)))

(defn create-entity! [{:keys [datapub-opts dataset-id file]}]
  (let [{:keys [content-type filename tempfile]} file
        fname (fs/base-name filename)]
    [(-> {:contentUpload (if (= "application/json" content-type)
                           (slurp tempfile)
                           tempfile)
          :datasetId dataset-id
          :mediaType content-type
          :metadata (when (not= "application/json" content-type)
                      (json/write-str {:filename fname}))}
          (dpc/create-dataset-entity! "id" datapub-opts)
          :id)]))

(defn get-ris-title [ris-map]
  (let [{:keys [primary-title secondary-title]} (ris/titles-and-abstract ris-map)]
    (or primary-title secondary-title)))

(defn create-ris-chunk! [sr-context {:keys [dataset-id project-id source-id]} entities]
  (doseq [chunk (partition-all 10 entities)]
    (db/with-long-tx [sr-context (assoc sr-context :tx-retry-opts {:n 1})]
      (doseq [{:keys [entity-id ris-map]} chunk]
        (let [article-data-id (-> sr-context
                                  (goc-article-data!
                                   {:dataset-id dataset-id
                                    :entity-id entity-id
                                    :content nil
                                    :title (get-ris-title ris-map)})
                                  :article-data/article-data-id)]
          (create-article! sr-context project-id source-id article-data-id))))))

(defn create-ris-chunks! [sr-context {:as ids :keys [project-id source-id]} entities]
  (doseq [chunk (partition-all 1000 entities)]
    (let [chunk (vec chunk)]
      (ul/retry
       {:interval-ms 60000
        :n 5
        :throw-pred (fn [e]
                      (not (some-> e ex-message (str/includes? "clj-http: status 500"))))}
              ;; Update import-date to avoid timing out on large imports
       (source/set-import-date source-id)
       (create-ris-chunk! sr-context ids chunk)
       (db/clear-project-cache project-id)))))

(defn create-ris-entities!
  [sr-context {:keys [datapub-opts dataset-id project-id source-id]
               {:as file :keys [filename tempfile]} :file}]
  (with-open [rdr (io/reader tempfile)]
    (let [ris-maps (try
                     (ris/reader->ris-maps rdr)
                     (catch Exception _))
          entities (->> ris-maps
                        (filter #(some-> % get-ris-title str/blank? not))
                        (map
                         (fn [m]
                           (let [s (ris/ris-map->str m)]
                             {:ris-map m
                              :entity-id
                              (-> {:contentUpload s
                                   :datasetId dataset-id
                                   :mediaType "application/x-research-info-systems"}
                                  (dpc/create-dataset-entity! "id" datapub-opts)
                                  :id)}))))]
      (if (empty? entities)
        (throw (ex-info "Invalid RIS file" {:file file}))
        (create-ris-chunks!
         sr-context
         {:dataset-id dataset-id :project-id project-id :source-id source-id}
         entities)))))

(defn create-xml-entities!
  [sr-context {:keys [datapub-opts dataset-id project-id source-id]
               {:keys [filename tempfile]} :file}]
  (with-open [rdr (io/reader tempfile)]
    (source/set-import-date source-id)
    (db/with-long-tx [sr-context sr-context]
      (doseq [record (->> rdr dxml/parse :content
                          (some #(when (= :records (:tag %)) %))
                          :content
                          (filter #(= :record (:tag %))))
              :let [{:as article :keys [primary-title raw secondary-title]} (endnote/load-endnote-record record)
                    entity-id (when (seq raw)
                                (-> {:contentUpload (->> raw .getBytes base64/encode (str/join ""))
                                     :datasetId dataset-id
                                     :mediaType "application/xml"}
                                    (dpc/create-dataset-entity! "id" datapub-opts)
                                    :id))
                    article-data-id (-> sr-context
                                        (goc-article-data!
                                         {:dataset-id dataset-id
                                          :entity-id entity-id
                                          :content (dissoc article :raw)
                                          :title (or primary-title secondary-title)})
                                        :article-data/article-data-id)]]
        (create-article! sr-context project-id source-id article-data-id)))))

(defn create-file-entities!
  [sr-context {:keys [datapub-opts dataset-id file filename project-id source-id]}]
  (let [entity-ids (create-entity! {:datapub-opts datapub-opts
                                    :dataset-id dataset-id
                                    :file file})]
    (db/with-long-tx [sr-context sr-context]
      (doseq [entity-id entity-ids]
        (let [article-data-id (-> sr-context
                                  (goc-article-data!
                                   {:dataset-id dataset-id
                                    :entity-id entity-id
                                    :content nil
                                    :title (fs/base-name filename)})
                                  :article-data/article-data-id)]
          (create-article! sr-context project-id source-id article-data-id))))))

(def media-type-handlers
  {"application/octet-stream" create-ris-entities!
   "application/x-research-info-systems" create-ris-entities!
   "application/xml" create-xml-entities!
   "text/xml" create-xml-entities!})

(defn create-entities! [sr-context project-id source-id dataset-id files]
  (let [datapub-opts (source/datapub-opts sr-context :upload? true)]
    (doseq [{:keys [content-type filename] :as file} files]
      ((or (media-type-handlers content-type) create-file-entities!)
       sr-context
       {:datapub-opts datapub-opts
        :dataset-id dataset-id
        :file file
        :filename filename
        :project-id project-id
        :source-id source-id}))))

(defn upload-files-to-s3!
  "Upload files to the sysrev imports bucket. This allows for easier
   debugging and the resumption of interrupted imports."
  [sr-context files]
  (->> files
       (mapv
        (fn [{:as file :keys [filename tempfile]}]
          (let [{:keys [key]}
                (file/save-s3-file sr-context :import filename {:file tempfile})]
            (assoc file :s3-key key))))))

(defn rebuild-files!
  "Recreate tempfiles from S3 when resuming an import."
  [sr-context dir {:keys [files]}]
  (->> files
       (mapv
        (fn [{:as file :keys [s3-key]}]
          (let [tempfile (-> dir (babashka.fs/path (str (random-uuid))) babashka.fs/file)]
            (babashka.fs/copy (s3/get-file-stream sr-context s3-key :import) tempfile)
            (assoc file :tempfile tempfile))))))

(defn import-files! [sr-context {:keys [files source-id]}]
  (let [{:project-source/keys [dataset-id meta project-id]}
        (->> {:select [:dataset-id :meta :project-id] :from :project-source :where [:= :source-id source-id]}
             (db/execute-one! sr-context))]
    (babashka.fs/with-temp-dir [dir {:prefix "sysrev-import"}]
      (->> (or files (rebuild-files! sr-context dir meta))
           (create-entities! sr-context project-id source-id dataset-id)))
    (source/alter-source-meta source-id #(assoc % :importing-articles? false))
    (db/clear-project-cache project-id)))

(defn import! [sr-context project-id files & {:keys [sync?]}]
  (let [files (upload-files-to-s3! sr-context files)
        dataset-id (:id (dpc/create-dataset!
                         {:description (str "Files uploaded for project " project-id)
                          :name (random-uuid)
                          :public false}
                         "id"
                         (source/datapub-opts sr-context)))
        {:as source :project-source/keys [source-id]}
        (create-source! sr-context project-id dataset-id files)
        {job-id :job/id} (insert-job! sr-context "import-files"
                                      {:source-id source-id
                                       :source-copy source} ;; In case the source is deleted, we still have a copy to debug the import
                                      :status "started" :started-at [:now])]
    (db/clear-project-cache project-id)
    ((if sync? deref identity)
     (future
       (util/log-errors
        (import-files! sr-context {:files files :source-id source-id})
        (->> {:update :job
              :set {:status "done"}
              :where [:= :id job-id]}
             (db/execute-one! sr-context)))))
    {:success true}))

(defn import-entities! [sr-context project-id source-id dataset-id entity-ids]
  (doseq [entity-id entity-ids]
    (db/with-long-tx [sr-context (assoc-in sr-context [:tx-retry-opts :n] 4)]
      (let [article-data-id (-> sr-context
                                (goc-article-data!
                                 {:dataset-id dataset-id
                                  :entity-id entity-id
                                  :content nil
                                  :title (str entity-id)})
                                :article-data/article-data-id)]
        (create-article! sr-context project-id source-id article-data-id)))))

(defn get-dataset-entity-ids [datapub-opts dataset-id & [cursor]]
  (lazy-seq
   (let [{edges :edges
          {:keys [endCursor hasNextPage]} :pageInfo}
         #__ (-> datapub-opts
                 (assoc :query (dpcq/q-dataset#entities "edges{node{id}} pageInfo{endCursor hasNextPage}")
                        :variables {:after cursor
                                    :id dataset-id})
                 dpc/execute!
                 :data :dataset :entities)]
     (concat
      (mapv (comp :id :node) edges)
      (when hasNextPage
        (get-dataset-entity-ids datapub-opts dataset-id endCursor))))))

(defn import-project-source-articles! [sr-context {:keys [source-id]}]
  (let [{:project-source/keys [dataset-id project-id]}
        #__ (->> {:select [:dataset-id :project-id]
                  :from :project-source
                  :where [:= :source-id source-id]}
                 (db/execute-one! sr-context))]
    (db/clear-project-cache project-id)
    (->> (get-dataset-entity-ids (source/datapub-opts sr-context) dataset-id)
         (import-entities! sr-context project-id source-id dataset-id))
    (source/alter-source-meta source-id  #(assoc % :importing-articles? false))
    (db/clear-project-cache project-id)))

(defn update-failed-jobs [sr-context]
  (db/with-long-tx [sr-context sr-context]
    (->> {:update :job
          :set {:status "failed"}
          :where [:and
                  [:= :status ["started"]]
                  [:>= :retries :max-retries]
                  [:not= nil :started-at]
                  [:>= [:now] [:+ :timeout :started-at]]]}
         (db/execute-one! sr-context))))

(defn get-jobs [sr-context type]
  (db/with-long-tx [sr-context sr-context]
    (update-failed-jobs sr-context)
    (->> {:select :*
          :from :job
          :limit 1
          :where [:and
                  [:= :type type]
                  [:or
                   [:= :status "new"]
                   [:and
                    [:= :status "started"]
                    [:< :retries :max-retries]
                    [:>= [:now] [:+ :timeout :started-at]]]]]}
         (db/execute! sr-context))))

(defn start-job! [sr-context job-id]
  (db/with-long-tx [sr-context sr-context]
    (->> {:update :job
          :set {:retries [:+ 1 :retries]
                :started-at [:now]}
          :where [:= :id job-id]}
         (db/execute! sr-context))))

(def job-type->fn
  {"import-files" import-files!
   "import-project-source-articles" import-project-source-articles!})

(defn import-from-job-queue! [sr-context]
  (util/log-errors
   (doseq [[type f] job-type->fn
           {:job/keys [id payload]} (get-jobs sr-context type)]
     (start-job! sr-context id)
     (f sr-context payload)
     (->> {:update :job
           :set {:status "done"}
           :where [:= :id id]}
          (db/execute-one! sr-context)))))
