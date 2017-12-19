(ns sysrev.import.endnote
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.articles :refer [add-article]]
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
