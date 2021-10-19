(ns sysrev.datapub-import.fda-drugs
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sysrev.datapub-client.interface.queries :as dpcq]
            [sysrev.fda-drugs.interface :as fda-drugs]
            [sysrev.file-util.interface :as file-util]
            [sysrev.sqlite.interface :as sqlite])
  (:import (java.util Base64)
           (org.apache.commons.io IOUtils)))

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

(defn doc-external-id [{:keys [ApplNo ApplicationDocsDescription Submissions]}]
  (pr-str [ApplNo ApplicationDocsDescription
           (:SubmissionType (first Submissions))]))

(defn upload-doc! [{:keys [ApplicationDocsURL] :as doc}
                   {:keys [auth-token dataset-id endpoint]}]
  (dpcq/m-create-dataset-entity!
   {:input
    {:content (->> (http/get ApplicationDocsURL {:as :stream})
                   :body
                   IOUtils/toByteArray
                   (.encodeToString (Base64/getEncoder)))
     :datasetId dataset-id
     :externalId (doc-external-id doc)
     :mediaType "application/pdf"
     :metadata (json/generate-string doc)}}
   #{:id}
   :auth-token auth-token
   :endpoint endpoint))

(def sql-create-fda-drugs-docs
  "CREATE TABLE IF NOT EXISTS fda_drugs_docs
   (url TEXT PRIMARY KEY,
    status TEXT NOT NULL)")

(defn init-db! [filename]
  (let [{:keys [datasource] :as sqlite} (component/start
                                         (sqlite/sqlite filename))]
    (sqlite/execute! datasource {:raw sql-create-fda-drugs-docs})
    sqlite))

(defn get-doc-status [connectable url]
  (sqlite/execute-one!
   connectable
   {:select :status
    :from :fda-drugs-docs
    :where [:= :url url]}))

(defn set-doc-status! [connectable url status]
  (sqlite/execute-one!
   connectable
   {:insert-into :fda-drugs-docs
    :values [{:status status :url url}]
    :on-conflict []
    :do-update-set {:status status}}))

(defn doc-stats [connectable]
  (sqlite/execute!
   connectable
   {:select [[:%count.* :count] :status]
    :from :fda-drugs-docs
    :group-by [:status]}))

(defn import-fda-drugs-doc!
  [{:keys [auth-token dataset-id endpoint sqlite] :as opts}
   {url :ApplicationDocsURL :as doc}]
  (let [datasource (:datasource sqlite)]
    (try
      (log/info (str "FDA@Drugs processing: " url))
      (set-doc-status! datasource url "processing")
      (log/info (str "FDA@Drugs upload successful: "
                     (pr-str (upload-doc! doc opts))))
      (set-doc-status! datasource url "uploaded")
      (catch Exception e
        (set-doc-status! datasource url "failed")
        (log/error
         (str "FDA@Drugs doc upload failed for \"" url
              "\": " (.getMessage e)))))))

(defn import-fda-drugs-docs!*
  [{:keys [auth-token dataset-id endpoint sqlite] :as opts} docs]
  (let [datasource (:datasource sqlite)]
    (log/info (str "FDA@Drugs doc stats: " (pr-str (doc-stats datasource))))
    ;; Docs need to be sorted by date (ascending) to make versioning by
    ;; externalId work properly.
    ;; We only handle labels currently.
    (doseq [{url :ApplicationDocsURL :as doc}
            (->> docs
                 (filter #(= "Label" (:ApplicationDocsDescription %)))
                 (sort-by :ApplicationDocsDate))]
      (when (and
             (str/ends-with? (str/lower-case url) ".pdf")
             (contains? #{nil "new"} (get-doc-status datasource url)))
        (import-fda-drugs-doc! opts doc)))
    (log/info (str "FDA@Drugs doc stats: " (pr-str (doc-stats datasource))))))

(defn import-fda-drugs-docs! [opts]
  (let [docs (docs-with-applications (get-applications!))]
    (import-fda-drugs-docs!*
     (assoc opts :sqlite (init-db! "fda-drugs.db"))
     docs)))
