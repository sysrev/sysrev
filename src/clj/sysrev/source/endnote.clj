(ns sysrev.source.endnote
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.data.xml :as dxml]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [sysrev.config.core :as config]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source import-source-impl]]
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

(defn endnote-file->articles [reader]
  (->> (load-endnote-library-xml reader)
       (map #(dissoc % :custom4 :custom5 :rec-number))))

(defmethod import-source :endnote-xml
  [stype project-id {:keys [file filename]} {:keys [use-future? threads] :as options}]
  (let [source-meta (source/make-source-meta :endnote-xml {:filename filename})]
    (import-source-impl
     project-id source-meta
     {:get-article-refs #(-> file io/reader endnote-file->articles doall)
      :get-articles identity}
     options)))
