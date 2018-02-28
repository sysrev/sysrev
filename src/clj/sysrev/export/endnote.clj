(ns sysrev.export.endnote
  (:require [clojure.xml :as xml]
            [clojure.data.xml :as dxml]
            [clojure.java.io :as io]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query]]
            [sysrev.util :refer
             [xml-find-value parse-xml-str today-string]]
            [sysrev.shared.util :refer [in? map-values]]
            [sysrev.db.queries :as q]
            [sysrev.db.labels :as labels]
            [sysrev.db.articles :as articles]
            [sysrev.import.endnote :refer
             [load-endnote-record parse-endnote-file]]
            [clojure.string :as str]))

(defn- all-included-articles [project-id]
  (->> (keys (labels/project-included-articles 100))
       (mapv
        (fn [article-id]
          (let [article (q/query-article-by-id article-id [:*])
                rec-number (xml-find-value
                            (-> article :raw parse-xml-str)
                            [:rec-number])]
            (assoc article :rec-number rec-number))))
       (group-by :rec-number)
       (map-values first)))

(defn- merge-record-xml
  "Add a new child element to content of an existing <record> element."
  [rxml tag & content]
  (apply dxml/element :record {}
         (dxml/element
          tag {}
          (apply dxml/element :style {:face "normal"
                                      :font "default"
                                      :size "100%"}
                 content))
         (:content rxml)))

(defn filter-endnote-articles [project-id exml]
  (let [articles (all-included-articles project-id)
        include-record?
        #(when-let [rec-number (xml-find-value % [:rec-number])]
           (when (contains? articles rec-number)
             (let [{:keys [article-uuid]} (get articles rec-number)]
               (-> %
                   (merge-record-xml :custom4 rec-number)
                   (merge-record-xml :custom5 (str article-uuid))))))
        records (->> exml :content first :content
                     (map include-record?)
                     (remove nil?))]
    (dxml/element
     :xml {}
     (apply dxml/element :records {}
            records))))

(defn filter-endnote-xml-includes
  "Create an EndNote XML export filtered to keep only included articles.

   `project-id` is the project containing inclusion labels.
   `in-path` is path to the EndNote XML file which project was imported from.
   `out-path` is path to write filtered EndNote XML file."
  [project-id in-path out-path]
  (spit out-path
        (as-> in-path f
          (parse-endnote-file f)
          (filter-endnote-articles project-id f)
          (dxml/emit-str f)
          ;; EndNote XML format seems to use \r
          (str/replace f #"\n" "\r")
          (str/trim-newline f)))
  true)

(defn- with-style [& content]
  (apply vector
         :style
         {:size "100%" :font "default" :face "normal"}
         content))

(defn article-to-endnote-xml
  [article-id {:keys [filename]
               :or {filename "Sysrev_Articles"}}]
  (let [article (articles/query-article-by-id-full
                 article-id {:include-disabled? true})]
    [:record
     [:database {:name (str filename ".enl")}
      (str filename ".enl")]
     [:source-app {:version "17.7" :name "EndNote"} "EndNote"]
     [:rec-number (str article-id)]
     [:ref-type {:name "Journal Article"} "17"]
     [:contributors
      (when (-> article :authors not-empty)
        [:authors
         (doall
          (->> article :authors
               (map (fn [s]
                      [:author (with-style s)]))))])]
     [:titles
      [:title
       (with-style (-> article :primary-title))]
      (when (-> article :secondary-title)
        [:secondary-title
         (with-style (-> article :secondary-title))])]
     (when (-> article :secondary-title)
       [:periodical
        [:full-title
         (with-style (-> article :secondary-title))]])
     #_ [:pages]
     #_ [:volume]
     #_ [:number]
     (when (-> article :keywords not-empty)
       [:keywords {}
        (doall
         (->> article :keywords
              (map (fn [s]
                     [:keyword (with-style s)]))))])
     (when (-> article :year)
       [:dates
        [:year (with-style (-> article :year str))]])
     #_ [:isbn]
     [:abstract (with-style (-> article :abstract))]
     #_ [:notes]
     [:urls
      (let [urls (concat
                  (-> article :urls)
                  (-> article :locations
                      articles/article-location-urls))]
        (when (not-empty urls)
          [:related-urls
           (doall
            (->> urls
                 (map (fn [s]
                        [:url (with-style s)]))))]))]
     (when-let [doi (-> article :locations (get "doi"))]
       [:electronic-resource-num
        (doall
         (->> doi
              (map (fn [x]
                     (with-style (-> x :external-id))))))])
     (when (-> article :remote-database-name)
       [:remote-database-name
        (with-style (-> article :remote-database-name))])
     [:custom4 (-> article-id str)]
     [:custom5 (-> article :article-uuid str)]]))

(defn project-to-endnote-xml [project-id]
  (let [filename (str "SysRev_Articles_"
                      project-id
                      "_"
                      (today-string))
        records (-> (q/select-project-articles project-id [:article-id])
                    (->> do-query
                         (map :article-id)
                         (map #(article-to-endnote-xml
                                % {:filename filename}))))]
    (dxml/emit-str
     (dxml/sexp-as-element
      [:xml
       [:records
        (doall records)]]))))
