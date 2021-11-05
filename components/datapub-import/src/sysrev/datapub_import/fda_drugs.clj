(ns sysrev.datapub-import.fda-drugs
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [lambdaisland.uri :as uri]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.fda-drugs.interface :as fda-drugs]
            [sysrev.file-util.interface :as file-util]
            [sysrev.sqlite.interface :as sqlite]))

(defn canonical-url
  "Lower-cases the URL and forces https://.
  www.accessdata.fda.gov URLs are case-insensitive."
  [s]
  (-> s
      str/lower-case
      uri/uri
      (assoc :scheme "https")
      str))

#_:clj-kondo/ignore
(defn get-applications! []
  (file-util/with-temp-file [path {:prefix "sysrev.datapub-import.fda-drugs-"
                                   :suffix ".zip"}]
    (fda-drugs/download-data! path)
    (fda-drugs/parse-applications path)))

(defn docs-with-applications
  "Returns each ApplicationDoc merged with its application data.

  The :Submissions are reordered such that the first submission always
  corresponds to the ApplicationDoc."
  [apps]
  (->> apps
       (mapcat
        (fn [[ApplNo {:keys [Submissions] :as app}]]
          (let [app* (-> (dissoc app :Submissions)
                         (assoc :ApplNo ApplNo))
                subs (mapv #(dissoc % :Docs) Submissions)]
            (->> Submissions
                 (mapcat
                  (fn [{:keys [Docs] :as sub}]
                    (let [sub* (dissoc sub :Docs)]
                      (->> Docs
                           (remove nil?)
                           (map
                            (fn [doc]
                              (-> (merge doc app*)
                                  (assoc :Submissions
                                         (into [sub*] (remove #{sub*} subs))))))))))))))))

(def sql-create-schema
  ["CREATE TABLE IF NOT EXISTS fda_drugs_docs (
      url TEXT PRIMARY KEY,
      appl_no TEXT NOT NULL,
      status TEXT NOT NULL
    )"
   "CREATE TABLE IF NOT EXISTS review_page_links (
      link_text TEXT NOT NULL,
      url TEXT NOT NULL,
      appl_no TEXT NOT NULL,
      source_url TEXT NOT NULL,
      PRIMARY KEY (link_text, url),
      FOREIGN KEY (source_url) REFERENCES fda_drugs_docs(url)
    )"])

(defn init-db! [filename]
  (let [{:keys [datasource] :as sqlite} (component/start
                                         (sqlite/sqlite filename))]
    (doseq [s sql-create-schema]
      (sqlite/execute! datasource {:raw s}))
    sqlite))

(defn get-doc-status [connectable url]
  (sqlite/execute-one!
   connectable
   {:select :status
    :from :fda-drugs-docs
    :where [:= :url url]}))

(defn put-doc! [connectable url values]
  (sqlite/execute-one!
   connectable
   {:insert-into :fda-drugs-docs
    :values [(assoc values :url url)]
    :on-conflict []
    :do-update-set values}))

(defn put-link! [connectable link-text url values]
  (sqlite/execute-one!
   connectable
   {:insert-into :review-page-links
    :values [(assoc values :link-text link-text :url url)]
    :on-conflict []
    :do-update-set values}))

(defn doc-stats [connectable]
  {:fda-drugs-docs
   (sqlite/execute!
    connectable
    {:select [[:%count.* :count] :status]
     :from :fda-drugs-docs
     :group-by [:status]})
   :review-page-links
   (sqlite/execute!
    connectable
    {:select [[:%count.* :count]]
     :from :review-page-links})})

(defn content-type* [s]
  (some-> s (str/split #";") first str/lower-case))

(defn convert-date
  "Changes FDA format (\"2020-10-05 00:00:00\") to datapub's ISO-8601 instant
  format (\"2020-10-05T00:00:00Z\")."
  [s]
  (str (str/replace s " " "T") "Z"))

(defn upload-doc! [connectable doc
                   {:keys [auth-token dataset-id endpoint]}]
  (let [doc-url (canonical-url (:ApplicationDocsURL doc))
        {:keys [body headers]} (http/get doc-url {:as :stream})
        content-type (-> headers (get "content-type") content-type*)]
    (case content-type
      "application/pdf"
      (->> (dpc/create-dataset-entity!
            {:contentUpload body
             :datasetId dataset-id
             :externalCreated (convert-date (:ApplicationDocsDate doc))
             :externalId doc-url
             :groupingId (pr-str [(:ApplNo doc)
                                  (:ApplicationDocsDescription doc)])
             :mediaType "application/pdf"
             :metadata (json/generate-string doc)}
            #{:id}
            :auth-token auth-token
            :endpoint endpoint)
           pr-str
           (str "FDA@Drugs upload successful: ")
           log/info)

      "text/html"
      (let [base-uri (uri/uri doc-url)
            pdf-links (fda-drugs/parse-review-html (slurp body))]
        (if (empty? pdf-links)
          (throw (ex-info "No links to PDFs found" {:url doc-url}))
          (do
            (doseq [{:keys [label url]} pdf-links]
              (let [absolute-url (canonical-url (str (uri/join base-uri url)))]
                (put-link!
                 connectable (str/lower-case label) absolute-url
                 {:appl-no (:ApplNo doc)
                  :source-url doc-url})))
            (log/info (str "Added " (count pdf-links) " review documents"))))))))

(defn upload-review-doc! [{:keys [auth-token dataset-id endpoint]}
                          {:keys [doc type url]}]
  (let [{:keys [body headers]} (http/get url {:as :stream})
        content-type (-> headers (get "content-type") content-type*)]
    (case content-type
      "application/pdf"
      (->> (dpc/create-dataset-entity!
            {:contentUpload body
             :datasetId dataset-id
             :externalCreated (convert-date (:ApplicationDocsDate doc))
             :externalId url
             :groupingId (pr-str [(:ApplNo doc)
                                  (:ApplicationDocsDescription doc)
                                  type])
             :mediaType "application/pdf"
             :metadata (json/generate-string
                        (assoc doc :ReviewDocumentType type))}
            #{:id}
            :auth-token auth-token
            :endpoint endpoint)
           pr-str
           (str "FDA@Drugs upload successful: ")
           log/info))))

(defn datapub-has-url? [opts url]
  (-> (dpc/execute!
       (assoc opts
              :query "query($id: PositiveInt! $externalId: String!){dataset(id: $id){entities(externalId: $externalId){totalCount}}}"
              :variables {:externalId (canonical-url url)
                          :id (:dataset-id opts)}))
      (get-in [:data :dataset :entities :totalCount])
      (#(not (or (nil? %) (zero? %))))))

(defn import-fda-drugs-doc!
  [{:keys [auth-token dataset-id endpoint sqlite] :as opts}
   {appl-no :ApplNo url :ApplicationDocsURL :as doc}]
  (let [datasource (:datasource sqlite)
        set-status! (fn [status]
                      (put-doc!
                       datasource url
                       {:appl-no appl-no
                        :status status}))]
    (if (datapub-has-url? opts url)
      (do
        (log/info (str "FDA@Drugs skipping URL, already on datapub: " url))
        (set-status! "uploaded"))
      (do
        (log/info (str "FDA@Drugs processing: " url))
        (set-status! "processing")
        (try
          (upload-doc! datasource doc opts)
          (set-status! "uploaded")
          (catch Exception e
            (set-status! "failed")
            (log/error
             (str "FDA@Drugs doc upload failed for \"" url
                  "\": " (.getMessage e)))
            (.printStackTrace e)))))))

(def review-type
  {#{"chemistry review(s)"} "chemistry review"
   #{"integrated review"} "integrated review"
   #{"medical review(s)"} "medical review"
   #{"microbiology review"} "microbiology review"
   #{"microbiology review(s)"} "microbiology review"
   #{"multi-discipline review"} "multi-discipline review"
   #{"pharmacology review(s)"} "pharmacology review"
   #{"proprietary name review(s)"} "proprietary name review"
   #{"risk assessment and risk mitigation review(s)"} "risk assessment and risk mitigation review"
   #{"statistical review"} "statistical review"
   #{"statistical review(s)"} "statistical review"
   #{"summary review"} "summary review"})

(defn import-review-docs!
  [{:keys [auth-token dataset-id endpoint sqlite] :as opts} docs urls]
  (let [datasource (:datasource sqlite)
        docs-by-appl-no (reduce
                         (fn [m {:keys [ApplNo] :as doc}]
                           (update m ApplNo (fnil conj []) doc))
                         {}
                         docs)]
    (doseq [url urls]
      (let [rows (sqlite/execute!
                  datasource
                  {:select :*
                   :from :review-page-links
                   :where [:= url :url]})
            link-texts (->> rows
                            (map :review-page-links/link-text)
                            (filter seq)
                            (into #{}))
            {:review-page-links/keys [appl-no source-url]} (first rows)
            type (review-type link-texts)
            set-status! (fn [status]
                          (put-doc!
                           datasource url
                           {:appl-no appl-no
                            :status status}))
            doc (->> (get docs-by-appl-no appl-no)
                     (some #(and (= source-url (canonical-url (:ApplicationDocsURL %)))
                                 %)))]
        (if (datapub-has-url? opts url)
          (do
            (log/info (str "FDA@Drugs skipping URL, already on datapub: " url))
            (set-status! "uploaded"))
          (do
            (log/info (str "FDA@Drugs review-type " (pr-str type)
                           " from link-texts " (pr-str link-texts)))
            (when type
              (log/info (str "FDA@Drugs processing: " url))
              (set-status! "processing")
              (try
                (upload-review-doc! opts {:doc doc :type type :url url})
                (set-status! "uploaded")
                (catch Exception e
                  (set-status! "failed")
                  (log/error
                   (str "FDA@Drugs doc upload failed for \"" url
                        "\": " (.getMessage e)))
                  (.printStackTrace e))))))))))

(defn import-fda-drugs-docs!*
  [{:keys [auth-token dataset-id endpoint sqlite] :as opts} docs]
  (let [datasource (:datasource sqlite)]
    (log/info (str "FDA@Drugs doc stats: " (pr-str (doc-stats datasource))))
    (doseq [{url :ApplicationDocsURL :as doc}
            (filter (comp #{"Label" "Letter" "Review"} :ApplicationDocsDescription) docs)]
      (when (contains? #{nil "new"} (get-doc-status datasource url))
        (import-fda-drugs-doc! opts doc)))
    (log/info (str "FDA@Drugs doc stats: " (pr-str (doc-stats datasource))))
    (let [new-review-urls (->> (sqlite/execute!
                                datasource
                                {:select [[[:distinct :review-page-links.url]]]
                                 :from :review-page-links
                                 :left-join [:fda-drugs-docs [:= :review-page-links.url :fda-drugs-docs.url]]
                                 :where [:or
                                         [:= nil :fda-drugs-docs.status]
                                         [:= "new" :fda-drugs-docs.status]]})
                               (map :review-page-links/url))]
      (when (seq new-review-urls)
        (log/info (str "Importing " (count new-review-urls) " new documents from review pages"))
        (import-review-docs! opts docs new-review-urls)
        (log/info (str "FDA@Drugs doc stats: " (pr-str (doc-stats datasource))))))))

(defn import-fda-drugs-docs! [opts]
  (let [docs (docs-with-applications (get-applications!))]
    (import-fda-drugs-docs!*
     (assoc opts :sqlite (init-db! "fda-drugs.db"))
     docs)))

(comment
  (do
    (def apps (get-applications!))
    (def docs (docs-with-applications apps))
    (def sqlite (init-db! "fda-drugs.db"))
    (def datasource (:datasource sqlite))
    (def opts {:sqlite sqlite}))

  (doc-stats datasource)

  ;; Frequencies of link-text sets (some pages have multiple links to the same URL).
  (->> (sqlite/execute! datasource
                        {:select [:link-text :url]
                         :from :review-page-links
                         :where [:not= "" :link-text]})
       (group-by :review-page-links/url)
       (map (fn [[_ v]]
              (mapv :review-page-links/link-text v)))
       frequencies
       (sort-by (comp - val))
       (mapv (comp vec reverse))
       doall)

  ;; Find pages with a specific link text for analysis.
  (->> (sqlite/execute! datasource
                        {:select :*
                         :from :review-page-links
                         :where [:= :link-text "part 1"]}))

  ;; Review page links with only "" link text
  ;; As of 2021-10-21 there were 7. All from 2000 or earlier, and all were 404s.
  ;; We can ignore these.
  (->> (sqlite/execute! datasource
                        {:select [:link-text :source-url :url]
                         :from :review-page-links})
       (group-by :review-page-links/url)
       (keep (fn [[_ v]]
               (when (= [""] (mapv :review-page-links/link-text v))
                 v)))))
