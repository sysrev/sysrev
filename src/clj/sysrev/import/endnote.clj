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
              parse-integer]]
            [sysrev.shared.util :refer [map-values]]
            [clojure.string :as str]
            [sysrev.db.queries :as q]
            [clojure.java.jdbc :as j]))

(defn parse-endnote-file [fname]
  (-> fname
      io/file io/reader dxml/parse))

(defn load-endnote-library-xml
  "Parse an Endnote XML file into a vector of article maps."
  [file]
  (let [x (if (string? file)
            (parse-endnote-file file)
            file)]
    (let [entries (xml-find [x] [:records :record])]
      (->>
       entries
       (map
        (fn [e]
          (->
           (merge
            (->>
             {:primary-title [:titles :title]
              :secondary-title [:titles :secondary-title]
              ;; :periodical [:periodical :full-title]
              :abstract [:abstract]
              :remote-database-name [:remote-database-name]
              :year [:dates :year]}
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
                     vec)))))
           (update :year parse-integer)
           (assoc :raw (dxml/emit-str e)))))))))

(defn import-endnote-library [file project-id]
  (let [articles (load-endnote-library-xml file)]
    (doseq [a articles]
      (println (pr-str (:primary-title a)))
      (add-article a project-id))))
