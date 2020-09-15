(ns sysrev.custom.ebtc
  (:require [clojure.data.xml :as dxml]
            [clojure.string :as str]
            [clojure.math.combinatorics :as combo]
            [clojure-csv.core :as csv]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.article.core :refer [set-article-flag]]
            [sysrev.export.endnote :as endnote]
            [sysrev.label.core :as labels]
            [sysrev.util :as util :refer [in?]]))

(defn label-possible-values [{:keys [label-id] :as label}]
  (case (:value-type label)
    "boolean"      [true false]
    "categorical"  (-> label :definition :all-values)
    nil))

(defn included-article-label-counts [project-id label-ids]
  (let [articles (labels/project-included-articles project-id)
        labels (project/project-labels project-id)
        combos
        (->> label-ids
             (mapv #(->> (label-possible-values (get labels %))
                         (concat [nil]) distinct))
             (apply combo/cartesian-product))
        get-article-combos
        (fn [article-id]
          (let [article (get articles article-id)
                lvalues (->> label-ids
                             (map (fn [label-id]
                                    (->> (get-in article [:labels label-id])
                                         (map :answer)
                                         (map #(if (sequential? %) % [%]))
                                         (apply concat)
                                         distinct)))
                             (map #(if (empty? %) [nil] %)))]
            (apply combo/cartesian-product lvalues)))
        article-combos
        (->> (keys articles)
             (map (fn [article-id]
                    [article-id (get-article-combos article-id)]))
             (apply concat)
             (apply hash-map))
        combo-counts
        (->> combos
             (map (fn [cvals]
                    (let [ccount
                          (->> (vals article-combos)
                               (filter #(in? % cvals))
                               count)]
                      [cvals ccount])))
             (apply concat)
             (apply hash-map))]
    (->> combos (map #(vector % (get combo-counts %))))))

(defn export-included-article-label-counts [project-id label-ids]
  (let [counts
        (->> (included-article-label-counts project-id label-ids)
             (map (fn [[lvalues count]]
                    [(map #(if (nil? %) "<not labeled>" %) lvalues)
                     count])))
        label-names
        (->> label-ids (map #(q/get-label % :name)))
        titles
        (concat label-names ["article_count"])
        output
        (->> counts (map (fn [[lvalues lcount]]
                           (concat (map str lvalues) [(str lcount)]))))]
    (csv/write-csv (concat [titles] output) :force-quote true)))

(defn disable-missing-abstracts [project-id min-length]
  (db/with-clear-project-cache project-id
    (doseq [article-id (q/find :article {:project-id project-id} :article-id
                               :where [:or
                                       [:= :abstract nil]
                                       [:= :%char_length.abstract 0]])]
      (set-article-flag article-id "no abstract" true))
    (doseq [article-id (q/find :article {:project-id project-id} :article-id
                               :where [:and
                                       [:!= :abstract nil]
                                       [:< :%char_length.abstract min-length]])]
      (set-article-flag article-id "short abstract" true {:min-length min-length}))
    true))

(defn filter-endnote-xml-includes
  "Create an EndNote XML export filtered to keep only included articles.

   `project-id` is the project containing inclusion labels.
   `in-path` is path to the EndNote XML file which project was imported from.
   `out-path` is path to write filtered EndNote XML file."
  [project-id in-path out-path]
  (spit out-path
        (as-> in-path f
          (endnote/parse-endnote-file f)
          (endnote/filter-endnote-articles project-id f)
          (dxml/emit-str f)
          ;; EndNote XML format seems to use \r
          (str/replace f #"\n" "\r")
          (str/trim-newline f)))
  true)

;; TODO: add export functionality in web Articles interface, delete this
(defn project-included-to-endnote-xml
  [project-id & {:keys [to-file]}]
  (let [filename (str "Sysrev_Included_" project-id "_" (util/today-string))
        article-ids (keys (labels/project-included-articles project-id))
        file (some-> to-file endnote/make-endnote-out-file)]
    (endnote/article-ids-to-endnote-xml article-ids filename :file file)))
