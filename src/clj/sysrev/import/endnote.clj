(ns sysrev.import.endnote
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute clear-project-cache with-transaction *conn*]]
            [sysrev.db.articles :as articles]
            [sysrev.db.project :as project]
            [sysrev.db.labels :as labels]
            [sysrev.db.documents :as docs]
            [sysrev.db.sources :as sources]
            [clojure.tools.logging :as log]
            [sysrev.util :refer
             [xml-find xml-find-vector xml-find-vector
              parse-integer parse-xml-str]]
            [sysrev.shared.util :refer [map-values to-uuid]]
            [clojure.string :as str]
            [sysrev.db.queries :as q]
            [clojure.java.jdbc :as j]))

(defn parse-endnote-file [fname]
  (-> fname io/file io/reader dxml/parse))

(defn- document-id-from-url [url]
  (second (re-matches #"^internal-pdf://(\d+)/.*" url)))

(defn load-endnote-record [e]
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
           (-> (xml-find [e] (concat path [:style]))
               first :content first))))
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
         (assoc parsed-xml :date (str (:year parsed-xml) " " (:pubdate parsed-xml)))))
      (dissoc :pubdate)
      (assoc :raw (dxml/emit-str e))))

(defn load-endnote-library-xml
  "Parse an Endnote XML file into a vector of article maps."
  [file]
  (let [x (cond (string? file)
                (parse-endnote-file file)
                (= java.io.File (type file))
                (-> file slurp dxml/parse-str)
                :else file)]
    (->> (-> x :content first :content)
         (mapv load-endnote-record))))

(defn load-endnote-doc-ids
  "Parse an Endnote XML file mapping `article-uuid` values (`custom5` field) to
   `document-id` values."
  [file]
  (->> (load-endnote-library-xml file)
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
      (throw (Exception.
              (str "exception in sysrev.import.endnote/add-article "
                   "article:" (:primary-title article) " "
                   "message: " (.getMessage e))))
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
                (log/debug (format "sysrev.import.endnote/import-articles-to-project!: * field `%s` is empty" (pr-str k)))))
            (when-let [article-id (add-article
                                   (-> article
                                       (dissoc :locations)
                                       (assoc :enabled false))
                                   project-id)]
              ;; associate this article with a project-source-id
              (sources/add-article-to-source! article-id project-source-id)
              (when (not-empty (:locations article))
                (-> (sqlh/insert-into :article-location)
                    (values
                     (->> (:locations article)
                          (mapv #(assoc % :article-id article-id))))
                    do-execute))))
          (catch Throwable e
            (throw (Exception. (str (format "sysrev.import.endnote/import-articles-to-project!: error importing article #%s"
                                            (:public-id article))
                                    ": " (.getMessage e))))))))
    (finally
      (clear-project-cache project-id))))

(defn add-articles!
  "Import articles into project-id using the meta map as a source description. If the optional keyword :use-future? true is used, then the importing is wrapped in a future"
  [articles project-id source-id meta & {:keys [use-future? threads]
                                         :or {use-future? false threads 1}}]
  (if (and use-future? (nil? *conn*))
    (future
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
                            (println "Error in sysrev.import.endnote/add-articles! (inner future)"
                                     (.getMessage e))
                            false)))))
                   (mapv deref))
              success? (every? true? thread-results)]
          (if success?
            (do (sources/update-project-source-metadata!
                 source-id (assoc meta :importing-articles? false)))
            (sources/fail-project-source-import! source-id))
          success?)
        (catch Throwable e
          (log/info "Error in sysrev.import.endnote/add-articles! (outer future)"
                    (.getMessage e))
          (sources/fail-project-source-import! source-id)
          false)
        (finally
          ;; update the enabled flag for the articles
          (sources/update-project-articles-enabled! project-id))))
    (try
      ;; import the data
      (let [success?
            (import-articles-to-project! articles project-id source-id)]
        (if success?
          (sources/update-project-source-metadata!
           source-id (assoc meta :importing-articles? false))
          (sources/fail-project-source-import! source-id))
        success?)
      (catch Throwable e
        (println "Error in sysrev.import.endnote/add-articles!"
                 (.getMessage e))
        (sources/fail-project-source-import! source-id))
      (finally
        ;; update the enabled flag for the articles
        (sources/update-project-articles-enabled! project-id)))))

(defn endnote-file->articles
  [file]
  (try (mapv #(dissoc % :custom4 :custom5 :rec-number) (load-endnote-library-xml file))
       (catch Throwable e
         (throw (Exception. "Error parsing file")))))

(defn import-endnote-library! [file filename project-id & {:keys [use-future? threads]
                                                           :or {use-future? false threads 1}}]
  (let [meta (sources/import-articles-from-endnote-file-meta filename)
        source-id (sources/create-project-source-metadata!
                   project-id
                   (assoc meta
                          :importing-articles? true))]
    (try (let [articles (endnote-file->articles file)]
           (add-articles! articles project-id source-id meta
                          :use-future? use-future?
                          :threads threads))
         (catch Throwable e
           (sources/fail-project-source-import! source-id)
           (throw (Exception. "Error in sysrev.import.endnote/import-endnote-library!: " (.getMessage e)))))))

(defn clone-subproject-endnote
  "Clones a project from the subset of articles in `parent-id` project that
  are contained in Endnote XML export file `endnote-path`. Also imports
  `document` entries using file `endnote-path` and directory `pdfs-path`,
  and attaches `document-id` values to article entries.

  The `endnote-path` file must be in the format created by
  `filter-endnote-xml-includes` (has the original `article-uuid` values
  attached to each Endnote article entry under field `:custom5`).

  Copies most project definition entries over from the parent project
  (eg. project members, label definitions, keywords).

  The `project` and `article` entries will reference the parent project using
  fields `parent-project-id` and `parent-article-uuid`."
  [project-name parent-id endnote-path pdfs-path]
  (let [article-doc-ids (load-endnote-doc-ids endnote-path)
        article-uuids (keys article-doc-ids)
        child-id
        (:project-id (project/create-project
                      project-name :parent-project-id parent-id))]
    (project/add-project-note child-id {})
    (println (format "created child project (#%d, '%s')"
                     child-id project-name))
    (project/populate-child-project-articles
     parent-id child-id article-uuids)
    (println (format "loaded %d articles"
                     (project/project-article-count child-id)))
    (docs/load-article-documents child-id pdfs-path)
    (docs/load-project-document-ids child-id article-doc-ids)
    (labels/copy-project-label-defs parent-id child-id)
    (project/copy-project-keywords parent-id child-id)
    (project/copy-project-members parent-id child-id)
    (println "clone-subproject-endnote done")))
