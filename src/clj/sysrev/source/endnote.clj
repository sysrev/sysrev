(ns sysrev.source.endnote
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.data.xml :as dxml]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [sysrev.config.core :as config]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.util :as util :refer
             [xml-find xml-find-vector xml-find-vector parse-xml-str]]
            [sysrev.shared.util :as sutil :refer [map-values to-uuid parse-integer]]))

(defn parse-endnote-file [fname]
  (-> fname io/file io/reader dxml/parse))

(defn- document-id-from-url [url]
  (and (string? url)
       (second (re-matches #"^internal-pdf://(\d+)/.*" url))))

(defn load-endnote-record [e]
  (if (nil? e)
    (do (log/info "load-endnote-record: null record entry") nil)
    (try
      (as-> (merge
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
                   (let [style-elt (xml-find [e] (concat path [:style]))
                         style-value (and (sequential? style-elt)
                                          (-> style-elt first :content first))
                         direct-elt (delay (xml-find [e] path))
                         direct-value (delay (and (sequential? @direct-elt)
                                                  (-> @direct-elt first :content first)))]
                     (cond (string? style-value)    style-value
                           (string? @direct-value)  @direct-value
                           :else nil))
                   (catch Throwable e
                     (log/warn "exception reading path " path)
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
               :keywords [:keywords :keyword]
               :web-urls [:urls :web-urls :url]
               :electronic-resource-num [:electronic-resource-num]}
              (map-values
               (fn [path]
                 (try
                   (let [elts (xml-find-vector [e] path)
                         style-vals (->> elts (map :content) (apply concat) vec not-empty)
                         direct-vals (delay (-> (xml-find-vector [e] path) vec))]
                     (or (not-empty style-vals) @direct-vals))
                   (catch Throwable e
                     (log/warn "exception reading path " path)
                     [])))))
             (->>
              {:document-ids [:urls :pdf-urls :url]}
              (map-values
               (fn [path]
                 (->> (xml-find-vector [e] path)
                      (map document-id-from-url)
                      (remove nil?)
                      vec)))))
          result
        (update result :year parse-integer)
        (assoc result :date
               (->> [(:year result) (:pubdate result)]
                    (remove nil?)
                    (str/join " ")))
        (dissoc result :pubdate)
        (assoc result :raw (dxml/emit-str e))
        (assoc result :urls (->> [(:urls result)
                                  (:web-urls result)
                                  (:electronic-resource-num result)]
                                 (apply concat) vec))
        (dissoc result :web-urls :electronic-resource-num)
        (do (doseq [field [:primary-title]]
              (when (empty? (get result field))
                (log/warn "load-endnote-record: missing field " field)))
            result)
        result)
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
       (map #(dissoc % :custom4 :custom5 :rec-number))
       ;; for testing missing titles and exception handling
       #_ (map #(case (util/crypto-rand-int 3)
                  0 %
                  1 (assoc % :primary-title nil)
                  2 (assoc % :primary-title (util/crypto-rand-int 100))))))

(defmethod make-source-meta :endnote-xml [_ {:keys [filename]}]
  {:source "EndNote file" :filename filename})

(defmethod import-source :endnote-xml
  [stype project-id {:keys [file filename]} {:as options}]
  (let [source-meta (source/make-source-meta :endnote-xml {:filename filename})
        project-sources (source/project-sources project-id)
        filename-sources (->> project-sources
                              (filter #(= (get-in % [:meta :filename]) filename)))]
    (cond
      (not-empty filename-sources)
      (do (log/warn "import-source endnote-xml - non-empty filename-sources - "
                    filename-sources)
          {:error {:message "File name already imported"}})

      :else
      (import-source-impl
       project-id source-meta
       {:get-article-refs #(-> file io/reader endnote-file->articles doall)
        :get-articles identity}
       options
       :filename filename :file file))))
