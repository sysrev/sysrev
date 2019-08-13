(ns sysrev.web.routes.project
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure-csv.core :as csv]
            [clojure.tools.logging :as log]
            [compojure.core :refer :all]
            [ring.util.response :as response]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.api :as api]
            [sysrev.web.app :as web :refer [wrap-authorize current-user-id active-project]]
            [sysrev.web.routes.core :refer [setup-local-routes]]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.project.core :as project]
            [sysrev.db.groups :as groups]
            [sysrev.db.documents :as docs]
            [sysrev.article.core :as article]
            [sysrev.label.core :as labels]
            [sysrev.label.answer :as answer]
            [sysrev.article.assignment :as assign]
            [sysrev.source.core :as source]
            [sysrev.db.files :as files]
            [sysrev.db.article-list :as alist]
            [sysrev.db.annotations :as annotations]
            [sysrev.biosource.importance :as importance]
            [sysrev.export.core :as export]
            [sysrev.export.endnote :refer [project-to-endnote-xml]]
            [sysrev.filestore :as fstore]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.predict.report :as predict-report]
            [sysrev.shared.keywords :as keywords]
            [sysrev.shared.transit :as sr-transit]
            [sysrev.pubmed :as pubmed]
            [sysrev.config.core :refer [env]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? parse-integer]])
  (:import [java.util UUID]
           (java.io File Writer InputStream ByteArrayInputStream)
           [org.apache.commons.io IOUtils]))

;;;
;;; Functions for project routes
;;;

(defn update-user-default-project [request]
  (let [user-id (current-user-id request)
        project-id (active-project request)]
    (when (and user-id project-id)
      (future
        (try
          (users/set-user-default-project user-id project-id)
          (users/update-member-access-time user-id project-id)
          (catch Throwable e
            (log/info "error updating default project")
            nil))))))

(defn prepare-article-response
  [{:keys [abstract primary-title secondary-title] :as article}]
  (let [keywords (project/project-keywords (:project-id article))]
    (cond-> article
      true (dissoc :raw)
      abstract
      (assoc :abstract-render
             (keywords/format-abstract abstract keywords))
      primary-title
      (assoc :title-render
             (keywords/process-keywords primary-title keywords))
      secondary-title
      (assoc :journal-render
             (keywords/process-keywords secondary-title keywords)))))

(defn article-info-full [project-id article-id]
  (let [[article user-labels user-notes article-pdfs
         [consensus resolve resolve-labels]]
        (pvalues (article/get-article article-id)
                 (labels/article-user-labels-map project-id article-id)
                 (article/article-user-notes-map project-id article-id)
                 (api/article-pdfs article-id)
                 (list (labels/article-consensus-status project-id article-id)
                       (labels/article-resolved-status project-id article-id)
                       (labels/article-resolved-labels project-id article-id)))]
    {:article (merge (prepare-article-response article)
                     {:pdfs (:files article-pdfs)}
                     {:review-status consensus}
                     {:resolve (merge resolve {:labels resolve-labels})})
     :labels user-labels
     :notes user-notes}))

(defn project-info [project-id]
  (with-project-cache project-id [:project-info]
    (let [[[fields users labels keywords notes members predict importance url-ids files documents owner plan subscription-lapsed?]
           [_ [status-counts progress]]
           [articles sources]]
          (pvalues [(q/query-project-by-id project-id [:*])
                    (project/project-users-info project-id)
                    (project/project-labels project-id true)
                    (project/project-keywords project-id)
                    (project/project-notes project-id)
                    (labels/project-members-info project-id)
                    (predict-report/predict-summary
                     (q/project-latest-predict-run-id project-id))
                    (api/important-terms project-id)
                    (try
                      (project/project-url-ids project-id)
                      (catch Throwable e
                        (log/info "exception in project-url-ids")
                        []))
                    (files/list-document-files-for-project project-id)
                    (docs/all-article-document-paths project-id)
                    (project/get-project-owner project-id)
                    (api/project-owner-plan project-id)
                    (api/subscription-lapsed? project-id)]
                   [(labels/query-public-article-labels project-id)
                    (pvalues (labels/project-article-status-counts project-id)
                             (labels/query-progress-over-time project-id 30))]
                   [(project/project-article-count project-id)
                    #_ (source/project-sources-basic project-id)
                    (source/project-sources project-id)])]
      {:project {:project-id project-id
                 :name (:name fields)
                 :project-uuid (:project-uuid fields)
                 :members members
                 :stats {:articles articles
                         :status-counts status-counts
                         :predict predict
                         :progress progress}
                 :labels labels
                 :keywords keywords
                 :notes notes
                 :settings (:settings fields)
                 :files files
                 :documents documents
                 :sources sources
                 :importance importance
                 :url-ids url-ids
                 :owner owner
                 :plan plan
                 :subscription-lapsed? subscription-lapsed?}
       :users users})))

;;;
;;; Manage references to export files generated for download.
;;;

(defonce project-export-refs (atom {}))

(defn get-project-exports [project-id]
  (get @project-export-refs project-id))

(defn add-project-export [project-id export-type tempfile &
                          [{:keys [user-id filters] :as extra}]]
  (assert (isa? (type tempfile) File))
  (let [entry (merge extra {:download-id (sutil/random-id 5)
                            :export-type export-type
                            :tempfile-path (str tempfile)
                            :added-time (db/sql-now)})]
    (swap! project-export-refs update-in [project-id] #(conj % entry))
    entry))

(defn create-export-tempfile [content]
  (let [tempfile (util/create-tempfile)]
    (with-open [w (io/writer tempfile)]
      (.write w content))
    tempfile))

;;;
;;; Route definitions
;;;

(setup-local-routes {:routes project-routes
                     :define dr
                     :finalize finalize-routes})

(dr (GET "/api/public-projects" request
         (wrap-authorize
          request {}
          (api/public-projects))))

(dr (GET "/api/project-info" request
         (wrap-authorize
          request {:allow-public true
                   :bypass-subscription-lapsed? true}
          (let [project-id (active-project request)
                valid-project
                (and (integer? project-id)
                     (project/project-exists? project-id :include-disabled? false))]
            (assert (integer? project-id))
            (if (not valid-project)
              {:error {:status 404
                       :type :not-found
                       :message (format "Project (%s) not found" project-id)}}
              (do (update-user-default-project request)
                  (project-info project-id)))))))

(dr (POST "/api/join-project" request
          (wrap-authorize
           request {:logged-in true}
           (let [project-id (active-project request)
                 user-id (current-user-id request)
                 session (assoc-in (:session request)
                                   [:identity :default-project-id]
                                   project-id)]
             (assert (nil? (project/project-member project-id user-id))
                     "join-project: User is already a member of this project")
             (project/add-project-member project-id user-id)
             (users/set-user-default-project user-id project-id)
             (with-meta
               {:result {:project-id project-id}}
               {:session session})))))

(dr (POST "/api/create-project" request
          (wrap-authorize
           request {:logged-in true}
           (let [project-name (-> request :body :project-name)
                 user-id (current-user-id request)]
             (assert (integer? user-id))
             (api/create-project-for-user! project-name user-id)))))

(dr (POST "/api/delete-project" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 user-id (current-user-id request)]
             (api/delete-project! project-id user-id)))))

(dr (GET "/api/lookup-project-url" request
         (wrap-authorize
          request {}
          {:result (let [url-id (-> request :params :url-id sutil/read-transit-str)
                         self-id (current-user-id request)
                         [project-url-id {:keys [user-url-id org-url-id]}] url-id
                         ;; TODO: lookup project-id from combination of owner/project names
                         project-id (project/project-id-from-url-id project-url-id)
                         owner (some-> project-id project/get-project-owner)
                         user-id (some-> user-url-id users/user-id-from-url-id)
                         org-id (some-> org-url-id groups/group-id-from-url-id)
                         user-match? (and user-id (= user-id (:user-id owner)))
                         org-match? (and org-id (= org-id (:group-id owner)))
                         owner-url? (boolean (or user-url-id org-url-id))
                         user-redirect? (and (not owner-url?) (:user-id owner))
                         org-redirect? (and (not owner-url?) (:group-id owner))
                         no-owner? (and (nil? owner) project-id)]
                     (cond
                       ;; TODO: remove this after migration to set owners
                       no-owner?       {:project-id project-id}
                       user-match?     {:project-id project-id :user-id user-id}
                       org-match?      {:project-id project-id :org-id org-id}
                       user-redirect?  {:project-id project-id :user-id (:user-id owner)}
                       org-redirect?   {:project-id project-id :org-id (:group-id owner)}
                       project-id      {:project-id project-id}
                       :else        nil))})))

(dr (GET "/api/query-register-project" request
         (wrap-authorize
          request {}
          (let [register-hash (-> request :params :register-hash)
                project-id (project/project-id-from-register-hash register-hash)]
            {:project (when project-id
                        (let [{:keys [name]} (q/query-project-by-id project-id [:name])]
                          {:project-id project-id :name name}))}))))

;; Returns map with full information on an article
(dr (GET "/api/article-info/:article-id" request
         (wrap-authorize
          request {:allow-public true}
          (let [project-id (active-project request)
                article-id (-> request :params :article-id parse-integer)]
            (let [{:keys [article] :as result} (article-info-full project-id article-id)]
              (when (= (:project-id article) project-id)
                (update-user-default-project request)
                result))))))

(dr (POST "/api/project-articles" request
          (wrap-authorize
           request {:allow-public true}
           (let [project-id (active-project request)
                 {:keys [text-search sort-by sort-dir lookup-count
                         n-count n-offset] :as args} (-> request :body)
                 n-count (some-> n-count parse-integer)
                 n-offset (some-> n-offset parse-integer)
                 lookup-count (some-> lookup-count str (= "true"))
                 text-search (not-empty text-search)
                 filters (vec (->> [(:filters args)
                                    (when text-search [{:text-search text-search}])]
                                   (apply concat) (remove nil?)))
                 query-result (alist/query-project-article-list
                               project-id (cond-> {}
                                            n-count (merge {:n-count n-count})
                                            n-offset (merge {:n-offset n-offset})
                                            (not-empty filters) (merge {:filters filters})
                                            sort-by (merge {:sort-by sort-by})
                                            sort-dir (merge {:sort-dir sort-dir})))]
             (update-user-default-project request)
             {:result (if lookup-count
                        (:total-count query-result)
                        (:entries query-result))}))))

;;;
;;; Charts data
;;;

(dr (GET "/api/important-terms" request
         (wrap-authorize
          request {:allow-public true}
          (let [{:keys [n]} (-> request :params)]
            (api/important-terms (active-project request) (parse-integer n))))))

(dr (GET "/api/prediction-histograms" request
         (wrap-authorize
          request {:allow-public true}
          (api/prediction-histogram (active-project request)))))

(dr (GET "/api/charts/label-count-data" request
         (wrap-authorize
          request {:allow-public true}
          (api/label-count-data (active-project request)))))

;;;
;;; Article import
;;;

(dr (POST "/api/import-articles/pubmed" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [{:keys [search-term]} (:body request)
                 project-id (active-project request)]
             (api/import-articles-from-search project-id search-term)))))

(dr (POST "/api/import-articles/pmid-file/:project-id" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [tempfile filename]} (get-in request [:params :file])]
             (api/import-articles-from-file project-id tempfile filename)))))

(dr (POST "/api/import-articles/endnote-xml/:project-id" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [tempfile filename]} (get-in request [:params :file])]
             (api/import-articles-from-endnote-file project-id tempfile filename)))))

(dr (POST "/api/import-articles/pdf-zip/:project-id" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [tempfile filename]} (get-in request [:params :file])]
             (api/import-articles-from-pdf-zip-file project-id tempfile filename)))))

;;;
;;; Article review
;;;

(dr (GET "/api/label-task" request
         (wrap-authorize
          request {:roles ["member"]}
          (update-user-default-project request)
          (if-let [{:keys [article-id today-count] :as task}
                   (assign/get-user-label-task (active-project request) (current-user-id request))]
            {:result (merge (article-info-full (active-project request) article-id)
                            {:today-count today-count})}
            {:result :none}))))

;; Sets and optionally confirms label values for an article
(dr (POST "/api/set-labels" request
          (wrap-authorize
           request {:roles ["member"]}
           (let [user-id (current-user-id request)
                 project-id (active-project request)
                 before-count (-> (labels/project-article-status-counts project-id)
                                  :reviewed)
                 {:keys [article-id label-values confirm? change? resolve?]
                  :as body} (-> request :body)]
             (assert (or change? resolve?
                         (not (labels/user-article-confirmed? user-id article-id))))
             (update-user-default-project request)
             (answer/set-user-article-labels user-id article-id label-values
                                             :imported? false
                                             :confirm? confirm?
                                             :change? change?
                                             :resolve? resolve?)
             (let [after-count (-> (labels/project-article-status-counts project-id)
                                   :reviewed)]
               (when (and (> after-count before-count)
                          (not= 0 after-count)
                          (= 0 (mod after-count 15)))
                 (predict-api/schedule-predict-update project-id)))
             {:result body}))))

(dr (POST "/api/set-article-note" request
          (wrap-authorize
           request {:roles ["member"]}
           (let [user-id (current-user-id request)
                 {:keys [article-id name content]} (:body request)]
             (article/set-user-article-note article-id user-id name content)
             {:result (:body request)}))))

;;;
;;; PubMed search
;;;

;; Note that transit-clj is not used with query params.
;; Therefore, the string request parameter 'page-number' is converted to an integer
;; before being passed to get-query-pmids
;;
;; Why not just pass the parameters in the body of the request?
;; In REST, a GET request should not return a response based on
;; the content of request body.
;; see: https://stackoverflow.com/questions/978061/http-get-with-request-body

;; Return a vector of PMIDs associated with the given search term
(dr (GET "/api/pubmed/search" request
         (wrap-authorize
          request {}
          (let [{:keys [term page-number]} (-> :params request)]
            (pubmed/get-search-query-response term (parse-integer page-number))))))

;; Return article summaries for a list of PMIDs
(dr (GET "/api/pubmed/summaries" request
         (wrap-authorize
          request {}
          (let [{:keys [pmids]} (-> :params request)]
            (pubmed/get-pmids-summary (mapv parse-integer (str/split pmids #",")))))))

;;;
;;; Project settings
;;;

(dr (GET "/api/project-settings" request
         (wrap-authorize
          request {:allow-public true
                   :bypass-subscription-lapsed? true}
          (let [project-id (active-project request)]
            {:settings (project/project-settings project-id)
             :project-name (-> (q/select-project-where [:= :project-id project-id] [:name])
                               do-query first :name)}))))

(dr (POST "/api/change-project-settings" request
          (wrap-authorize
           request {:roles ["admin"]
                    :bypass-subscription-lapsed? true}
           (let [project-id (active-project request)
                 {:keys [changes]} (:body request)]
             (api/change-project-settings project-id changes)))))

(dr (POST "/api/change-project-name" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [project-name]} (:body request)]
             (project/change-project-name project-id project-name)
             {:success true, :project-name project-name}))))

(dr (POST "/api/change-project-permissions" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [users-map]} (:body request)]
             (api/change-project-permissions project-id users-map)))))

(dr (POST "/api/sync-project-labels" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [labels]} (:body request)]
             (api/sync-labels project-id labels)))))

(dr (GET "/api/project-description" request
         (wrap-authorize
          request {:allow-public true}
          (api/read-project-description (active-project request)))))

(dr (POST "/api/project-description" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 markdown (-> request :body :markdown)]
             (api/set-project-description! project-id markdown)))))

;;;
;;; Project sources
;;;

(dr (GET "/api/project-sources" request
         (wrap-authorize
          request {:allow-public true}
          (api/project-sources (active-project request)))))

(dr (POST "/api/delete-source" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [source-id (-> request :body :source-id)
                 user-id (current-user-id request)]
             (api/delete-source! source-id)))))

(dr (POST "/api/toggle-source" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [{:keys [source-id enabled?]} (-> request :body)
                 user-id (current-user-id request)]
             (api/toggle-source source-id enabled?)))))

(dr (GET "/api/sources/download/:project-id/:source-id" request
         (wrap-authorize
          request {:allow-public true}
          (let [project-id (active-project request)
                source-id (parse-integer (-> request :params :source-id))
                {:keys [key filename]} (source/source-upload-file source-id)]
            (-> (response/response (fstore/get-file-stream key :import))
                (response/header "Content-Disposition"
                                 (format "attachment; filename=\"%s\"" filename)))))))

;;;
;;; Project document files
;;;

(dr (GET "/api/files/:project-id" request
         (wrap-authorize
          request {:allow-public true}
          (let [project-id (active-project request)
                files (files/list-document-files-for-project project-id)]
            {:result (vec files)}))))

(dr (GET "/api/files/:project-id/download/:file-key" request
         (wrap-authorize
          request {:allow-public true}
          (let [project-id (active-project request)
                {:keys [file-key]} (:params request)
                filename (:name (->> (files/list-document-files-for-project project-id)
                                     (filter #(= file-key (str (:file-id %))))
                                     first))]
            (-> (response/response (fstore/get-file-stream file-key :document))
                (response/header "Content-Disposition"
                                 (format "attachment; filename=\"%s\"" filename)))))))

(dr (POST "/api/files/:project-id/upload" request
          (wrap-authorize
           request {:roles ["member"]}
           (let [project-id (active-project request)
                 file-data (get-in request [:params :file])
                 file (:tempfile file-data)
                 filename (:filename file-data)
                 user-id (current-user-id request)]
             (fstore/save-document-file
              project-id user-id filename file)
             {:result 1}))))

(dr (POST "/api/files/:project-id/delete/:key" request
          (wrap-authorize
           ;; TODO: This should be file owner or admin?
           request {:roles ["member"]}
           (let [project-id (active-project request)
                 key (-> request :params :key)
                 deletion (files/mark-document-file-deleted (UUID/fromString key) project-id)]
             {:result deletion}))))

;;
;; Project export files
;;

(dr (GET "/api/project-exports" request
         (wrap-authorize
          request {:allow-public true}
          (get-project-exports (active-project request)))))

(dr (POST "/api/generate-project-export/:project-id/:export-type" request
          (wrap-authorize
           request {:allow-public true}
           (let [project-id (active-project request)
                 user-id (current-user-id request)
                 export-type (-> request :params :export-type keyword)
                 {:keys [filters text-search separator]} (:body request)
                 text-search (not-empty text-search)
                 filters (vec (concat filters (when text-search [{:text-search text-search}])))
                 article-ids (when filters
                               (alist/query-project-article-ids {:project-id project-id} filters))
                 tempfile (case export-type
                            :user-answers
                            (-> (export/export-user-answers-csv
                                 project-id :article-ids article-ids :separator separator)
                                (csv/write-csv)
                                (create-export-tempfile))
                            :group-answers
                            (-> (export/export-group-answers-csv
                                 project-id :article-ids article-ids :separator separator)
                                (csv/write-csv)
                                (create-export-tempfile))
                            :articles-csv
                            (-> (export/export-articles-csv
                                 project-id :article-ids article-ids :separator separator)
                                (csv/write-csv)
                                (create-export-tempfile))
                            :annotations-csv
                            (-> (export/export-annotations-csv
                                 project-id :article-ids article-ids :separator separator)
                                (csv/write-csv)
                                (create-export-tempfile))
                            :endnote-xml
                            (project-to-endnote-xml
                             project-id :article-ids article-ids :to-file true))
                 {:keys [download-id]
                  :as entry} (add-project-export
                              project-id export-type tempfile
                              {:user-id user-id :filters filters :separator separator})
                 filename-base (case export-type
                                 :user-answers     "UserAnswers"
                                 :group-answers    "Answers"
                                 :endnote-xml      "Articles"
                                 :articles-csv     "Articles"
                                 :annotations-csv  "Annotations")
                 filename-ext (case export-type
                                (:user-answers
                                 :group-answers
                                 :articles-csv
                                 :annotations-csv)  "csv"
                                :endnote-xml        "xml")
                 filename-project (str "P" project-id)
                 filename-articles (if article-ids (str "A" (count article-ids)) "ALL")
                 filename-date (util/today-string "MMdd")
                 filename (str (->> [filename-base filename-project filename-date filename-articles]
                                    (str/join "_"))
                               "." filename-ext)]
             {:entry (-> (select-keys entry [:download-id :export-type :added-time])
                         (assoc :filename filename
                                :url (str/join "/" ["/api/download-project-export" project-id
                                                    (name export-type) download-id filename])))}))))

(dr (GET "/api/download-project-export/:project-id/:export-type/:download-id/:filename" request
         (wrap-authorize
          request {:allow-public true}
          (let [project-id (active-project request)
                export-type (-> request :params :export-type keyword)
                {:keys [download-id filename]} (-> request :params)
                entry (->> (get-project-exports project-id)
                           (filter #(and (= (:export-type %) export-type)
                                         (= (:download-id %) download-id)))
                           first)
                file (some-> entry :tempfile-path io/file)]
            (cond (empty? filename) (web/make-error-response
                                     api/bad-request :file "No filename given")
                  (nil? file) (web/make-error-response
                               api/not-found :file "Export file not found")
                  :else (case export-type
                          (:user-answers :group-answers :articles-csv :annotations-csv)
                          (-> (io/reader file) (web/csv-file-response filename))
                          :endnote-xml
                          (-> (io/reader file) (web/xml-file-response filename))))))))

;; Legacy route for existing API code
(dr (GET "/api/export-user-answers-csv/:project-id/:filename" request
         (wrap-authorize
          request {:allow-public true}
          (-> (export/export-user-answers-csv (active-project request))
              (csv/write-csv)
              (web/csv-file-response (-> request :params :filename))))))

;;;
;;; Project support subscriptions
;;;

(dr (GET "/api/user-support-subscriptions" request
         (wrap-authorize
          request {:logged-in true}
          (api/user-support-subscriptions
           (users/get-user (current-user-id request))))))

(dr (GET "/api/current-support" request
         (wrap-authorize
          request {:logged-in true}
          (api/current-project-support-level
           (users/get-user (current-user-id request))
           (active-project request)))))

(dr (POST "/api/cancel-project-support" request
          (wrap-authorize
           request {:logged-in true}
           (api/cancel-project-support
            (users/get-user (current-user-id request))
            (active-project request)))))

;;;
;;; PDF files
;;;

(dr (GET "/api/open-access/:article-id/availability" [article-id]
         (api/open-access-available? (parse-integer article-id))))

;; TODO: article-id is ignored; check value or remove
(dr (GET "/api/open-access/:article-id/view/:key" [article-id key]
         (-> (response/response (fstore/get-file-stream key :pdf))
             (response/header "Content-Type" "application/pdf"))))

(dr (POST "/api/files/:project-id/article/:article-id/upload-pdf" request
          (wrap-authorize
           request {:roles ["member"]}
           (let [{:keys [article-id]} (:params request)]
             (let [file-data (get-in request [:params :file])
                   file (:tempfile file-data)
                   filename (:filename file-data)]
               (api/save-article-pdf (parse-integer article-id) file filename))))))

(dr (GET "/api/files/:project-id/article/:article-id/article-pdfs" request
         (wrap-authorize
          request {:roles ["member"]}
          (let [{:keys [article-id]} (:params request)]
            (api/article-pdfs (parse-integer article-id))))))

(dr (GET "/api/files/:project-id/article/:article-id/download/:key" request
         (wrap-authorize
          request {:roles ["member"]}
          (let [key (-> request :params :key)
                article-id (parse-integer (-> request :params :article-id))
                {:keys [filename]} (->> (files/get-article-file-maps article-id)
                                        (filter #(= key (str (:key %))))
                                        first)]
            (-> (response/response (fstore/get-file-stream key :pdf))
                (response/header "Content-Type" "application/pdf")
                (response/header "Content-Disposition"
                                 (format "attachment; filename=\"%s\"" filename)))))))

(dr (GET "/api/files/:project-id/article/:article-id/view/:key" request
         (wrap-authorize
          request {:roles ["member"]}
          (let [{:keys [key]} (:params request)]
            (-> (response/response (fstore/get-file-stream key :pdf))
                (response/header "Content-Type" "application/pdf"))))))

(dr (POST "/api/files/:project-id/article/:article-id/delete/:key" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [key (-> request :params :key)
                 article-id (parse-integer (-> request :params :article-id))
                 {:keys [filename]} (->> (files/get-article-file-maps article-id)
                                         (filter #(= key (str (:key %))))
                                         first)]
             (if (not= (article/article-project-id article-id) (active-project request))
               {:error {:status api/not-found
                        :message (str "Article " article-id " not found in project")}}
               (api/dissociate-article-pdf article-id key filename))))))

;;;
;;; Article annotations
;;;

(dr (POST "/api/annotation/create" request
          (wrap-authorize
           request {:roles ["member"]}
           (with-transaction
             (let [{:keys [context annotation-map]} (-> request :body)
                   {:keys [selection annotation semantic-class]} annotation-map
                   {:keys [class article-id pdf-key]} context
                   user-id (current-user-id request)
                   project-id (active-project request)
                   result (condp = class
                            "abstract"
                            (do (assert (nil? pdf-key))
                                (api/save-article-annotation
                                 project-id article-id user-id selection annotation
                                 :context (:context annotation-map)))
                            "pdf"
                            (do (assert pdf-key)
                                (api/save-article-annotation
                                 project-id article-id user-id selection annotation
                                 :context (:context annotation-map) :pdf-key pdf-key)))]
               (when (and (string? semantic-class)
                          (not-empty semantic-class)
                          (:annotation-id result))
                 (api/update-annotation! (:annotation-id result)
                                         annotation semantic-class user-id))
               result)))))

(dr (POST "/api/annotation/update/:annotation-id" request
          (wrap-authorize
           request {:roles ["member"]}
           (let [annotation-id (-> request :params :annotation-id parse-integer)
                 {:keys [annotation semantic-class]} (-> request :body)
                 user-id (current-user-id request)]
             (api/update-annotation!
              annotation-id annotation semantic-class user-id)))))

(dr (POST "/api/annotation/delete/:annotation-id" request
          (wrap-authorize
           request {:roles ["member"]}
           (let [annotation-id (-> request :params :annotation-id parse-integer)]
             (api/delete-annotation! annotation-id)))))

(dr (GET "/api/annotation/status" request
         (wrap-authorize
          request {:allow-public true}
          (let [project-id (active-project request)
                user-id (current-user-id request)]
            (api/project-annotation-status project-id :user-id user-id)))))

(dr (GET "/api/annotations/user-defined/:article-id" request
         (let [article-id (-> request :params :article-id parse-integer)]
           (api/user-defined-annotations article-id))))

(dr (GET "/api/annotations/user-defined/:article-id/pdf/:pdf-key" request
         (let [article-id (-> request :params :article-id parse-integer)
               pdf-key (-> request :params :pdf-key)]
           (api/user-defined-pdf-annotations article-id pdf-key))))

#_(dr (GET "/api/annotations/:article-id" request
         (wrap-authorize
          request {:allow-public true}
          (let [article-id (-> request :params :article-id parse-integer)]
            (api/article-abstract-annotations article-id)))))

;;;
;;; Funding and compensation
;;;

(dr (POST "/api/paypal/add-funds" request
          (wrap-authorize
           request {:logged-in true}
           (let [project-id (active-project request)
                 {:keys [user-id response]} (:body request)]
             (api/add-funds-paypal project-id user-id response)))))

(dr (POST "/api/project-compensation" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [rate]} (:body request)]
             (api/create-project-compensation! project-id rate)))))

(dr (GET "/api/project-compensations" request
         (wrap-authorize
          request {:roles ["admin"]}
          (let [project-id (active-project request)]
            (api/read-project-compensations project-id)))))

(dr (PUT "/api/toggle-compensation-enabled" request
         (wrap-authorize
          request {:roles ["admin"]}
          (let [project-id (active-project request)
                {:keys [compensation-id enabled]} (:body request)]
            (api/toggle-compensation-enabled! project-id compensation-id enabled)))))

(dr (GET "/api/get-default-compensation" request
         (wrap-authorize
          request {:roles ["admin"]}
          (api/get-default-compensation (active-project request)))))

(dr (PUT "/api/set-default-compensation" request
         (wrap-authorize
          request {:roles ["admin"]}
          (let [project-id (active-project request)
                {:keys [compensation-id]} (:body request)]
            (api/set-default-compensation! project-id compensation-id)))))

(dr (GET "/api/compensation-owed" request
         (wrap-authorize
          request {:roles ["admin"]}
          (api/compensation-owed (active-project request)))))

(dr (GET "/api/project-users-current-compensation" request
         (wrap-authorize
          request {:roles ["admin"]}
          (api/project-users-current-compensation (active-project request)))))

(dr (PUT "/api/set-user-compensation" request
         (wrap-authorize
          request {:roles ["admin"]}
          (let [project-id (active-project request)
                {:keys [user-id compensation-id]} (:body request)]
            (api/set-user-compensation! project-id user-id compensation-id)))))

(dr (GET "/api/project-funds" request
         (wrap-authorize
          request {:roles ["admin"]}
          (api/project-funds (active-project request)))))

(dr (PUT "/api/check-pending-transaction" request
         (wrap-authorize
          request {:roles ["admin"]}
          (api/check-pending-project-transactions! (active-project request)))))

(dr (POST "/api/pay-user" request
          (wrap-authorize
           request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [user-id compensation admin-fee]} (-> request :body)]
             (api/pay-user! project-id user-id compensation admin-fee)))))

;;;
;;; Developer-only requests
;;;

(dr (POST "/api/delete-member-labels" request
          (wrap-authorize
           request {:roles ["member"]
                    :developer true}
           (let [user-id (current-user-id request)
                 project-id (active-project request)
                 {:keys [verify-user-id]} (:body request)]
             (assert (= user-id verify-user-id) "verify-user-id mismatch")
             (project/delete-member-labels-notes project-id user-id)
             {:success true}))))

(dr (POST "/api/update-project-predictions" request
          (wrap-authorize
           request {:developer true}
           (let [project-id (active-project request)]
             (assert (integer? project-id))
             (api/update-project-predictions project-id)))))

(finalize-routes)
