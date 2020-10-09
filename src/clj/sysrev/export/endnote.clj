(ns sysrev.export.endnote
  (:require [clojure.data.xml :as dxml]
            [clojure.data.xml node prxml]
            [clojure.java.io :as io]
            [sysrev.db.core :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.article.core :as article]
            [sysrev.util :as util]))

(defn- with-style [& content]
  (apply vector
         :style
         {:size "100%" :font "default" :face "normal"}
         content))

(defn article-to-endnote-xml
  [article-id {:keys [filename] :or {filename "Articles"}}]
  (let [article (article/get-article article-id :items [:locations])]
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
                      article/article-location-urls))]
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

(defn article-ids-to-endnote-xml [article-ids filename & {:keys [file]}]
  (-> [:xml [:records (pmap #(article-to-endnote-xml % {:filename filename})
                            article-ids)]]
      (dxml/sexp-as-element)
      (as-> element
          (if file
            (with-open [writer (io/writer file)]
              (dxml/emit element writer)
              (.flush writer)
              file)
            (dxml/emit-str element)))))

(defn make-endnote-out-file [to-file]
  (cond (nil? to-file)   nil
        (true? to-file)  (util/create-tempfile :suffix ".xml")
        :else            (io/file to-file)))

(defn project-to-endnote-xml
  [project-id & {:keys [to-file article-ids filename]}]
  (let [article-ids (some-> article-ids (set))
        export-ids (->> (q/select-project-articles project-id [:article-id])
                        do-query (map :article-id)
                        (filter (if article-ids #(contains? article-ids %) identity)))]
    (article-ids-to-endnote-xml
     export-ids
     (or filename (str "Articles_" project-id "_" (util/today-string)))
     :file (some-> to-file (make-endnote-out-file)))))
