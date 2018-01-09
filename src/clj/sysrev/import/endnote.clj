(ns sysrev.import.endnote
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.articles :refer [add-article]]
            [sysrev.db.project :as project]
            [sysrev.db.labels :as labels]
            [sysrev.db.documents :as docs]
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
         :custom5 [:custom5]}
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
      (assoc :raw (dxml/emit-str e))))

(defn load-endnote-library-xml
  "Parse an Endnote XML file into a vector of article maps."
  [file]
  (let [x (if (string? file)
            (parse-endnote-file file)
            file)]
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

(defn import-endnote-library [file project-id]
  (let [articles (load-endnote-library-xml file)]
    (doseq [a articles]
      (println (pr-str (:primary-title a)))
      (let [a (-> a (dissoc :custom4 :custom5 :rec-number))]
        (add-article a project-id)))))

(defn clone-subproject-endnote
  "Clones a project from the subset of articles in `parent-id` project that
  are contained in Endnote XML export file `endnote-path`. Also imports
  `document` entries using file `endnote-path` and directory `pdfs-path`,
  and attaches `document-id` values to article entries.

  The `endnote-path` file must be in the format created by
  `filter-endnote-xml-includes` (has the original `article-uuid` values
  attached to each Endnote article entry under field `:custom5`.

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

#_ (clone-subproject-endnote
    "EBTC Tox21 - Full Text Screening"
    100
    "/home/jeff/EBTC_Tox21_included_615_ids_ft_sysrev.xml"
    "/var/www/sysrev-docs/PDF/118/")
