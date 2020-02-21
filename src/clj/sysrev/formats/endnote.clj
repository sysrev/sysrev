(ns sysrev.formats.endnote
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [sysrev.util :as util :refer [xml-find xml-find-vector xml-find-vector
                                          map-values parse-integer]]))

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
                   (catch Throwable _
                     (log/warn "exception reading path " path))))))
             (->> {:rec-number [:rec-number]
                   :custom4 [:custom4]
                   :custom5 [:custom5]}
                  (map-values (fn [path]
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
                   (catch Throwable _
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
