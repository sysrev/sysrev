(ns sysrev.export.core
  (:require [clojure-csv.core :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [honeysql.helpers :as sqlh :refer [merge-join merge-where order-by]]
            [medley.core :as medley]
            [sysrev.api :refer [graphql-request]]
            [sysrev.article.core :as article]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.queries :as q]
            [sysrev.export.endnote :as endnote]
            [sysrev.file.article :as article-file]
            [sysrev.file.s3 :as s3-file]
            [sysrev.job-queue.interface :as jq]
            [sysrev.label.core :as label]
            [sysrev.project.article-list :as alist]
            [sysrev.project.core :as project]
            [sysrev.util :as util :refer [in? index-by]]
            [venia.core :as venia])
  (:import [java.io File]
           [java.util.zip ZipEntry ZipOutputStream]))

(def default-csv-separator "|||")

(defn stringify-csv-value
  "Formats x as a string to use as a CSV column value."
  [separator x]
  (let [separator (or separator default-csv-separator)]
    (cond (and (seqable? x) (empty? x))  ""
          (sequential? x)                (str/join separator (map str x))
          :else                          (str x))))

(def utf8-bom (String. (byte-array (mapv int [239 187 191]))))

(defn write-csv
  "Return a string of `table` in CSV format, with the UTF-8 BOM added for
  Excel.

  See https://www.edmundofuentes.com/blog/2020/06/13/excel-utf8-csv-bom-string/"
  [table & opts]
  (str utf8-bom
       (apply csv/write-csv table opts)))

(defn create-export-tempfile [^String content]
  (let [tempfile (util/create-tempfile)]
    (with-open [w (io/writer tempfile)]
      (.write w content))
    tempfile))

(defn create-job! [{:keys [job-queue]} payload]
  (jq/create-job!
   job-queue
   "generate-project-export"
   payload
   :max-retries 1))

(defn get-export [sr-context authorized-project-id job-id]
  (db/with-tx [sr-context sr-context]
    (let [{:job/keys [payload status]}
          #__ (->> {:select [:payload :status]
                    :from :job
                    :where [:and
                            [:= :id job-id]
                            [:= :type "generate-project-export"]]}
                   (db/execute-one! sr-context))
          {:keys [project-id]} payload]
      (when (= authorized-project-id project-id)
        (let [{:project-export/keys [filename url]}
              #__ (when (= "done" status)
                    (->> {:select [:filename :url]
                          :from :project-export
                          :where [:= :job-id job-id]}
                         (db/execute-one! sr-context)))]
          {:error nil
           :filename filename
           :status status
           :url url})))))

(defn project-labeled-article-ids [project-id]
  (q/find-article
   {:a.project-id project-id} :a.article-id
   :where (q/exists [:article-label :al] {:a.project-id project-id
                                          :al.article-id :a.article-id
                                          :l.enabled true}
                    :join [[:label :l] :al.label-id]
                    :prepare #(q/filter-valid-article-label % true))
   :with []))

(defn export-user-answers-csv
  "Returns CSV-printable list of raw user article answers. The first row
  contains column names; each following row contains answers for one
  value of (user,article). article-ids optionally specifies a subset
  of articles within project to include; by default, all enabled
  articles will be included."
  [sr-context project-id & {:keys [article-ids separator]}]
  (db/with-long-transaction [_ (:postgres sr-context)]
    (let [all-labels (-> (q/select-label-where
                            project-id
                            [:= nil :root-label-id-local]
                            [:label-id :short-label :value-type])
                         (order-by :project_ordering :label-id-local) do-query)
          all-articles (-> (project-labeled-article-ids project-id)
                           (ds-api/get-articles-content))
          user-answers (-> (q/select-project-articles
                            project-id [:al.article-id :al.label-id :al.user-id :al.answer
                                        :al.resolve :al.updated-time :u.email])
                           (q/join-article-labels)
                           (q/filter-valid-article-label true)
                           (q/join-article-label-defs)
                           (merge-where [:= :l.enabled true])
                           (q/join-users :al.user-id)
                           (->> do-query
                                (group-by #(vector (:article-id %) (:user-id %)))
                                vec (sort-by first) (mapv second)))
          user-notes (-> (q/select-project-articles
                          project-id [:anote.article-id :anote.user-id :anote.content])
                         (merge-join [:article-note :anote]
                                     [:= :anote.article-id :a.article-id])
                         (->> do-query
                              (group-by #(vector (:article-id %) (:user-id %)))
                              (medley/map-vals first)))]
      (concat
       [(concat ["Article ID" "Article Title" "User Name" "Resolve?"]
                (map :short-label all-labels)
                ["User Note" "Title" "Journal" "Authors"])]
       (for [user-article (cond->> user-answers
                            (seq article-ids)
                            (filter #(in? article-ids (-> % first :article-id))))]
         (let [{:keys [article-id user-id email]} (first user-article)
               resolved? (-> (:user-id (label/article-resolved-status project-id article-id))
                             (= user-id))
               user-name (first (str/split email #"@"))
               user-note (:content (get user-notes [article-id user-id]))
               {:keys [primary-title secondary-title authors]} (get all-articles article-id)
               label-answers (map (fn [{:keys [label-id value-type]}]
                                    (-> (filter #(= (:label-id %) label-id) user-article)
                                        first :answer
                                        (cond->
                                         (= "annotation" value-type) (->> vals (map :semantic-class))
                                         (= "group" value-type) boolean)))
                                  all-labels)
               all-authors (str/join "; " (map str authors))]
           (mapv (partial stringify-csv-value separator)
                 (concat [article-id primary-title user-name (if (true? resolved?) true nil)]
                         label-answers
                         [user-note primary-title secondary-title all-authors]))))))))

(defn export-article-answers-csv
  "Returns CSV-printable list of combined group answers for
  articles. The first row contains column names; each following row
  contains answers for one article. article-ids optionally specifies a
  subset of articles within project to include; by default, all
  enabled articles will be included."
  [sr-context project-id & {:keys [article-ids separator]}]
  (db/with-long-transaction[_ (:postgres sr-context)]
    (let [project-url (str "https://sysrev.com/p/" project-id)
          all-labels (-> (q/select-label-where
                          project-id
                          [:= nil :root-label-id-local]
                          [:label-id :short-label :value-type])
                         (order-by :project_ordering :label-id-local) do-query)
          all-articles (-> (project-labeled-article-ids project-id)
                           (ds-api/get-articles-content))
          anotes (-> (q/select-project-articles
                      project-id [:anote.article-id :anote.user-id :anote.content])
                     (merge-join [:article-note :anote]
                                 [:= :anote.article-id :a.article-id])
                     (->> do-query (group-by :article-id)))
          aanswers (-> (q/select-project-articles
                        project-id [:al.article-id :al.label-id :al.user-id :al.answer
                                    :al.updated-time :u.email])
                       (q/join-article-labels)
                       (q/filter-valid-article-label true)
                       (q/join-article-label-defs)
                       (merge-where [:= :l.enabled true])
                       (q/join-users :al.user-id)
                       (->> do-query (group-by :article-id)))]
      (concat
       [(concat ["Article ID" "Article URL" "Status" "User Count" "Users"]
                (map :short-label all-labels)
                ["User Notes" "Title" "Journal" "Authors"])]
       (for [article-id (sort (keys (cond-> aanswers
                                      (seq article-ids) (select-keys article-ids))))]
         (let [{:keys [primary-title secondary-title authors]} (get all-articles article-id)
               user-names (->> (get aanswers article-id) (map :email) distinct
                               (map #(first (str/split % #"@"))))
               user-count (count user-names)
               user-notes (map :content (get anotes article-id))
               consensus (label/article-consensus-status project-id article-id)
               resolved-labels (label/article-resolved-labels project-id article-id)
               get-label-values
               #__ (fn [{:keys [label-id value-type]}]
                     (as-> (if (seq resolved-labels)
                             (get resolved-labels label-id)
                             (->> (get aanswers article-id)
                                  (map
                                   (fn [{:keys [answer] :as resolved}]
                                     (when (= label-id (:label-id resolved))
                                       (cond
                                         (= "annotation" value-type) (->> answer
                                                                          vals
                                                                          (map :semantic-class))
                                         (= "group" value-type) [(boolean answer)]
                                         (sequential? answer) answer
                                         :else [answer]))))
                                  (apply concat) distinct sort))
                         $
                       (if (sequential? $) $ [$])
                       (if (= "group" value-type)
                         (if (#{[false] [nil]} $) [false] [true])
                         $)))
               all-authors (str/join "; " (map str authors))
               all-notes (str/join "; " (map pr-str user-notes))
               article-url (str project-url "/article/" article-id)]
           (mapv (partial stringify-csv-value separator)
                 (concat [article-id article-url (name (or consensus :none))
                          user-count user-names]
                         (map get-label-values all-labels)
                         [all-notes primary-title secondary-title all-authors]))))))))

;; TODO: include article external urls in export
(defn export-articles-csv
  "Returns CSV-printable list of articles with their basic fields. The
  first row contains column names; each following row contains fields
  for one article. Article prediction scores are also
  included. article-ids optionally specifies a subset of articles
  within project to include; by default, all enabled articles will be
  included."
  [sr-context project-id & {:keys [article-ids separator]}]
  (db/with-long-transaction [_ (:postgres sr-context)]
    (let [project-url (str "https://sysrev.com/p/" project-id)
          article-ids (or (seq article-ids) (project/project-article-ids project-id))
          all-articles (ds-api/get-articles-content article-ids)
          predict-run-id (q/project-latest-predict-run-id project-id)
          predict-label-ids [(project/project-overall-label-id project-id)]
          ;; TODO: select labels by presence of label_predicts entries
          predict-labels (when (and predict-run-id (seq predict-label-ids))
                           (-> (q/select-label-where
                                project-id [:in :l.label-id predict-label-ids]
                                [:l.label-id :l.label-id-local :l.short-label])
                               (->> do-query (index-by :label-id))))
          apredicts (when (and predict-run-id (seq predict-label-ids))
                      (-> (q/select-project-articles
                           project-id [:lp.article-id :lp.label-id [:lp.val :score]])
                          (q/join-article-predict-values predict-run-id)
                          (merge-where [:in :lp.label-id predict-label-ids])
                          (->> do-query
                               (group-by :article-id)
                               (medley/map-vals (partial map #(dissoc % :article-id)))
                               (medley/map-vals
                                #(->> (group-by :label-id %)
                                      ((partial medley/map-vals (comp :score first))))))))]
      (concat
       [(concat ["Article ID" "Article URL" "Title" "Journal" "Authors" "Abstract"]
                (for [label-id predict-label-ids]
                  (let [x (get predict-labels label-id)]
                    (str "predict(" (:label-id-local x) ":" (:short-label x) ")"))))]
       (for [article-id (sort (keys all-articles))]
         (let [{:keys [title primary-title secondary-title authors
                       abstract]} (get all-articles article-id)
               all-authors (str/join "; " (map str authors))
               article-url (str project-url "/article/" article-id)
               predict-scores (for [label-id predict-label-ids]
                                (when-let [score (get-in apredicts [article-id label-id])]
                                  (format "%.3f" score)))]
           (mapv (partial stringify-csv-value separator)
                 (concat [article-id article-url (or primary-title title)  secondary-title
                          all-authors abstract]
                         predict-scores))))))))

(defn export-annotations-csv
  "Returns CSV-printable list of user annotations in project. The first
  row contains column names; each following row contains fields for
  one annotation. article-ids optionally specifies a subset of
  articles within project to include; by default, all enabled articles
  will be included."
  [sr-context project-id & {:keys [article-ids separator]}]
  (db/with-long-transaction [_ (:postgres sr-context)]
    (->>
     (-> (q/select-project-articles
          project-id [:al.article-id :al.label-id :al.user-id :al.answer
                      :l.short-label])

         (q/join-article-labels)
         (q/filter-valid-article-label true)
         (q/join-article-label-defs)
         (cond-> (seq article-ids)
           (merge-where [:in :al.article-id article-ids]))
         (merge-where [:and
                       [:= :l.enabled true]
                       [:= :l.value-type "annotation"]])
         (q/join-users :al.user-id)
         do-query)
     (mapcat
      (fn [{:keys [answer article-id short-label user-id]}]
        (for [[_ {:keys [selection semantic-class value]}] answer]
          (mapv (partial stringify-csv-value separator)
                [article-id user-id short-label semantic-class selection value]))))
     (cons
      ["Article ID" "User ID" "Annotation Label" "Semantic Class" "Selection" "Value"]))))

(defn export-group-label-csv
  "Export the group label-id in project-id"
  [sr-context project-id & {:keys [article-ids separator label-id]}]
  (db/with-long-transaction [_ (:postgres sr-context)]
    (let [graphql-resp (->> (graphql-request
                             (venia/graphql-query
                              {:venia/queries
                               [[:project {:id project-id}
                                 [:name :id :date_created
                                  [:groupLabelDefinitions [:enabled :name :question :required :type :id :ordering [:labels [:id :consensus :enabled :name :question :required :type :ordering]]]]
                                  [:articles [:enabled :id
                                              [:groupLabels [[:answer
                                                              [:id :answer :name :question :required :type]]
                                                             :confirmed :consensus :created :id :name :question :required :updated :type
                                                             [:reviewer [:id :name]]]]]]]]]}))
                            :data :project
                            util/sanitize-uuids)
          group-label-defs (->> (get-in graphql-resp [:groupLabelDefinitions])
                                (medley/find-first #(= (:id %) label-id))
                                :labels
                                (filterv :enabled)
                                (medley/index-by :id))
          header (->> (concat ["Article ID" "User ID" "User Name"] (mapv :name (->> (vals group-label-defs)
                                                                                    (sort-by :ordering))))
                      (into []))
          process-group-answers (fn [answers]
                                  (let [answered-label-ids (set (map :id answers))
                                        non-answered-labels (->> group-label-defs vals
                                                                 (filter #(not (contains? answered-label-ids (:id %)))))
                                        empty-answers (map #(-> %
                                                                (select-keys [:id :name :question :required :type])
                                                                (assoc :answer []))
                                                           non-answered-labels)]
                                    (->> (concat answers empty-answers)
                                         (mapv
                                           #(assoc % :ordering (:ordering (get group-label-defs (:id %)))))
                                         (sort-by :ordering)
                                         (mapv (fn [{:keys [answer]}]
                                                 (stringify-csv-value separator answer))))))
          process-group-labels (fn [group-label]
                                 (let [{:keys [id name]} (:reviewer group-label)
                                       answer  (:answer group-label)]
                                   (->> (mapv process-group-answers answer)
                                        (mapv (partial concat [(str id) name])))))
          process-articles (fn [article]
                             (let [article-id (:id article)]
                               (->> article
                                    :groupLabels
                                    (filterv #(= (:id %) label-id))
                                    (mapv process-group-labels)
                                    (mapv (partial concat [(str article-id)])))))
          rows (->> graphql-resp
                    :articles
                    (mapv process-articles)
                    (remove (comp not seq))
                    (mapv #(->> %
                                (mapv (fn [coll]
                                        (mapv (partial concat [(first coll)]) (rest coll))))
                                (apply concat)))
                    (apply concat)
                    (into []))]
      (cons header rows))))

(defn project-json [project-id]
  (let [req [[:project {:id project-id}
              [:name :id :date_created
               [:labelDefinitions
                [:consensus :enabled :name :question :required :type]]
               [:groupLabelDefinitions
                [:enabled :name :question :required :type
                 [:labels [:consensus :enabled :name :question :required :type]]]]
               [:articles
                [:datasource_id :enabled :id :uuid
                 [:groupLabels
                  [[:answer
                    [:id :answer :name :question :required :type]]
                   :confirmed :consensus :created :id :name :question
                   :required :updated :type
                   [:reviewer [:id :name]]]]
                 [:labels
                  [:answer :confirmed :consensus :created :id :name :question
                   :required :updated :type
                   [:reviewer [:id :name]]]]]]]]]
        resp (graphql-request (util/gquery req))]
    (if (:errors resp)
      {:status 500
       :errors (:errors resp)}
      (:data resp))))

(defn article-pdfs
  "Given an article-id, return a vector of maps that correspond to the
  files associated with article-id"
  [article-id]
  (let [pmcid-s3-id (some-> article-id article/article-pmcid article-file/pmcid->s3-id)]
    {:success true
     :files (->> (article-file/get-article-file-maps article-id)
                 (mapv #(assoc % :open-access?
                               (= (:s3-id %) pmcid-s3-id))))}))

(defn project-article-pdfs-zip
  "download all article pdfs associated with a project. name pdf by article-id"
  [sr-context project-id]
  (let [articles (project/project-article-ids project-id)
        pdfs (mapcat (fn [aid]
                       (->> (:files (article-pdfs aid))
                            (map-indexed (fn [i art]
                                           (if (= i 0)
                                             {:key (:key art) :name (format "%s.pdf" aid)}
                                             {:key (:key art) :name (format "%s-%d.pdf" aid i)})))))
                     articles)
        tmpzip (util/create-tempfile :suffix (format "%d.zip" project-id))]
    (with-open [zip (ZipOutputStream. (io/output-stream tmpzip))]
      (doseq [f pdfs]
        (util/ignore-exceptions
         (with-open [is ^java.io.Closeable (s3-file/get-file-stream sr-context (:key f) :pdf)]
           (.putNextEntry zip (ZipEntry. (str (:name f))))
           (io/copy is zip)))))
    tmpzip))

;;;
;;; Manage references to export files generated for download.
;;;

(defonce project-export-refs (atom {}))

(defn get-project-exports [project-id]
  (get @project-export-refs project-id))

(defn add-project-export [project-id export-type tempfile &
                          [{:keys [filters] :as extra}]]
  (assert (isa? (type tempfile) File))
  (let [entry (merge extra {:download-id (util/random-id 5)
                            :export-type export-type
                            :tempfile-path (str tempfile)
                            :added-time db/sql-now})]
    (swap! project-export-refs update-in [project-id] #(conj % entry))
    entry))

(defn generate-project-export! [sr-context job-id payload]
  (let [{:keys [export-type filters label-id project-id separator]} payload
        export-type (keyword export-type)
        article-ids (when filters
                      (alist/query-project-article-ids {:project-id project-id} filters))
        tempfile (case export-type
                   :user-answers
                   (-> (export-user-answers-csv
                        sr-context
                        project-id :article-ids article-ids :separator separator)
                       write-csv
                       create-export-tempfile)
                   :article-answers
                   (-> (export-article-answers-csv
                        sr-context
                        project-id :article-ids article-ids :separator separator)
                       write-csv
                       create-export-tempfile)
                   :articles-csv
                   (-> (export-articles-csv
                        sr-context
                        project-id :article-ids article-ids :separator separator)
                       write-csv
                       create-export-tempfile)
                   :annotations-csv
                   (-> (export-annotations-csv
                        sr-context
                        project-id :article-ids article-ids :separator separator)
                       write-csv
                       create-export-tempfile)
                   :endnote-xml
                   (endnote/project-to-endnote-xml
                    project-id :article-ids article-ids :to-file true)
                   :group-label-csv
                   (-> (export-group-label-csv sr-context project-id :label-id label-id)
                       write-csv
                       create-export-tempfile)
                   :json
                   (-> (project-json project-id)
                       json/write-str
                       create-export-tempfile)
                   :uploaded-article-pdfs-zip
                   (project-article-pdfs-zip sr-context project-id))
        {:keys [download-id]
         :as entry} (add-project-export
                     project-id export-type tempfile
                     {:filters filters :separator separator})
        filename-base (case export-type
                        :user-answers     "UserAnswers"
                        :article-answers  "Answers"
                        :endnote-xml      "Articles"
                        :articles-csv     "Articles"
                        :annotations-csv  "Annotations"
                        :group-label-csv  "GroupLabel"
                        :uploaded-article-pdfs-zip "UPLOADED_PDFS"
                        :json             "JSON")
        filename-ext (case export-type
                       (:user-answers
                        :article-answers
                        :articles-csv
                        :annotations-csv
                        :group-label-csv)  "csv"
                       :endnote-xml        "xml"
                       :json               "json"
                       :uploaded-article-pdfs-zip "zip")
        filename-project (str "P" project-id)
        filename-articles (if article-ids (str "A" (count article-ids)) "ALL")
        filename-date (util/today-string "MMdd")
        filename (str (->> [filename-base filename-project filename-date (if (= export-type :group-label-csv)  (str "Group-Label-" (-> label-id label/get-label :short-label)) filename-articles)]
                           (str/join "_"))
                      "." filename-ext)]
   (->> {:insert-into :project-export
         :values [(-> (select-keys entry [:download-id])
                      (assoc :export-type (name export-type)
                             :filename filename
                             :job-id job-id
                             :url (str/join "/" ["/api/download-project-export" project-id
                                                 (name export-type) download-id
                                                 (str/replace filename "/" "%2F")])))]}
        (db/execute-one! sr-context))))
