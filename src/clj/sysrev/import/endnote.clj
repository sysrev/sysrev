(ns sysrev.import.endnote
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer
             [do-query do-execute clear-project-cache with-transaction *conn*]]
            [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.db.labels :as labels]
            [sysrev.db.documents :as docs]
            [sysrev.db.sources :as sources]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.biosource.importance :as importance]
            [sysrev.db.queries :as q]
            [sysrev.util :refer
             [xml-find xml-find-vector xml-find-vector parse-xml-str]]
            [sysrev.shared.util :refer [map-values to-uuid parse-integer]]))

(defn parse-endnote-file [fname]
  (-> fname io/file io/reader dxml/parse))

(defn- document-id-from-url [url]
  (second (re-matches #"^internal-pdf://(\d+)/.*" url)))

(defn load-endnote-record [e]
  (if (nil? e)
    (do (log/info "load-endnote-record: null record entry") nil)
    (try
      (-> (merge
           (->>
            {:primary-title [:titles :title]
             :secondary-title [:titles :secondary-title]
             ;; :periodical [:periodical :full-title]
             :abstract [:abstract]
             :remote-database-name [:remote-database-name]
             :year [:dates :year]
             :rec-number [:rec-number]
             :custom4 [:custom4]
             :custom5 [:custom5]
             :pubdate [:dates :pub-dates :date]}
            (map-values
             (fn [path]
               (try
                 (-> (xml-find [e] (concat path [:style]))
                     first :content first)
                 (catch Throwable e
                   (log/info "load-endnote-record: missed path " path)
                   nil)))))
           (->>
            {:rec-number [:rec-number]
             :custom4 [:custom4]
             :custom5 [:custom5]}
            (map-values
             (fn [path]
               (or (-> (xml-find [e] (concat path [:style]))
                       first :content first)
                   (-> (xml-find [e] path)
                       first :content first)))))
           (->>
            {:urls [:urls :related-urls :url]
             :authors [:contributors :authors :author]
             :keywords [:keywords :keyword]}
            (map-values
             (fn [path]
               (->> (xml-find-vector [e] path)
                    (map #(-> % :content))
                    (apply concat)
                    vec))))
           (->>
            {:document-ids [:urls :pdf-urls :url]}
            (map-values
             (fn [path]
               (->> (xml-find-vector [e] path)
                    (map document-id-from-url)
                    (remove nil?)
                    vec)))))
          (update :year parse-integer)
          ((fn [parsed-xml]
             (assoc parsed-xml :date
                    (str (:year parsed-xml) " " (:pubdate parsed-xml)))))
          (dissoc :pubdate)
          (assoc :raw (dxml/emit-str e)))
      (catch Throwable exc
        (log/info "load-endnote-record:" (type exc) "-" (.getMessage exc))
        nil))))

(defn load-endnote-library-xml
  "Parse Endnote XML from a Reader into a vector of article maps."
  [reader]
  (some->> (dxml/parse reader)
           :content first :content
           (pmap load-endnote-record)))

(defn load-endnote-doc-ids
  "Parse an Endnote XML file mapping `article-uuid` values (`custom5` field) to
   `document-id` values."
  [reader]
  (->> (load-endnote-library-xml reader)
       (map (fn [entry]
              (let [entry
                    (-> entry
                        (select-keys [:custom5 :document-ids])
                        (#(assoc % :article-uuid (to-uuid (:custom5 %))))
                        (dissoc :custom5))]
                [(:article-uuid entry)
                 (:document-ids entry)])))
       (apply concat)
       (apply hash-map)))

(defn- add-article [article project-id]
  (try
    (articles/add-article article project-id *conn*)
    (catch Throwable e
      (log/warn (format "%s: %s"
                        "sysrev.import.endnote/add-article"
                        (pr-str {:article article
                                 :message (.getMessage e)})))
      nil)))

(defn- import-articles-to-project!
  "Imports articles into project."
  [articles project-id project-source-id]
  (try
    (doseq [articles-group (->> articles (partition-all 20))]
      (doseq [article (->> articles-group (remove nil?))]
        (try
          (with-transaction
            (doseq [[k v] article]
              (when (or (nil? v)
                        (and (coll? v) (empty? v)))
                (log/debug
                 (format "%s: * field `%s` is empty"
                         "sysrev.import.endnote/import-articles-to-project!"
                         (pr-str k)))))
            (when-let [article-id (add-article
                                   (-> article
                                       (dissoc :locations)
                                       (assoc :enabled false))
                                   project-id)]
              ;; associate this article with a project-source-id
              (sources/add-article-to-source article-id project-source-id)
              (when (not-empty (:locations article))
                (-> (sqlh/insert-into :article-location)
                    (values
                     (->> (:locations article)
                          (mapv #(assoc % :article-id article-id))))
                    do-execute))))
          (catch Throwable e
            (let [message
                  (format "%s: error importing article #%s [%s]"
                          "sysrev.import.endnote/import-articles-to-project!"
                          (:public-id article)
                          (.getMessage e))]
              (log/warn message)
              (throw (Exception. message)))))))
    (finally
      (clear-project-cache project-id))))

(defn add-articles!
  "Import articles into project-id using the meta map as a source description. If the optional keyword :use-future? true is used, then the importing is wrapped in a future"
  [articles project-id source-id & {:keys [use-future? threads]
                                    :or {use-future? false threads 1}}]
  (if (and use-future? (nil? *conn*))
    (future
      (let [success?
            (try
              (let [thread-groups
                    (->> articles
                         (partition-all (max 1 (quot (count articles) threads))))
                    thread-results
                    (->> thread-groups
                         (mapv
                          (fn [thread-articles]
                            (future
                              (try
                                (import-articles-to-project!
                                 thread-articles project-id source-id)
                                true
                                (catch Throwable e
                                  (log/warn "Error in sysrev.import.endnote/add-articles! (inner future)"
                                            (.getMessage e))
                                  false)))))
                         (mapv deref))]
                (every? true? thread-results))
              (catch Throwable e
                (log/warn "Error in sysrev.import.endnote/add-articles! (outer future)"
                          (.getMessage e))
                false))]
        (with-transaction
          ;; update source metadata
          (if success?
            (sources/alter-source-meta
             source-id #(assoc % :importing-articles? false))
            (sources/fail-source-import source-id))
          ;; update the enabled flag for the articles
          (sources/update-project-articles-enabled project-id))
        (when success?
          (predict-api/schedule-predict-update project-id)
          (importance/schedule-important-terms-update project-id))
        success?))
    (let [success?
          (try
            (import-articles-to-project! articles project-id source-id)
            (catch Throwable e
              (log/warn "Error in sysrev.import.endnote/add-articles!"
                        (.getMessage e))
              false))]
      (with-transaction
        ;; update source metadata
        (if success?
          (sources/alter-source-meta
           source-id #(assoc % :importing-articles? false))
          (sources/fail-source-import source-id))
        ;; update the enabled flag for the articles
        (sources/update-project-articles-enabled project-id))
      (when success?
        (predict-api/schedule-predict-update project-id)
        (importance/schedule-important-terms-update project-id))
      success?)))

(defn endnote-file->articles [reader]
  (->> (load-endnote-library-xml reader)
       (map #(dissoc % :custom4 :custom5 :rec-number))))

(defn import-endnote-library!
  [file filename project-id & {:keys [use-future? threads]
                               :or {use-future? false threads 1}}]
  (let [source-id (sources/create-source
                   project-id
                   (-> (sources/make-source-meta :endnote-xml {:filename filename})
                       (assoc :importing-articles? true)))
        do-import
        (fn []
          (try (let [articles (doall (endnote-file->articles (io/reader file)))]
                 (add-articles! articles project-id source-id
                                :use-future? use-future?
                                :threads threads))
               (catch Throwable e
                 (let [message
                       (format "%s: %s"
                               "sysrev.import.endnote/import-endnote-library!"
                               (.getMessage e))]
                   (log/warn message)
                   (sources/fail-source-import source-id)
                   (throw e)))))]
    (if use-future?
      (do (future (do-import))
          {:result {:success true}})
      (do (do-import)
          {:result {:success true}}))))
