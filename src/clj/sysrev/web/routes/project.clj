(ns sysrev.web.routes.project
  (:require [sysrev.api :as api]
            [sysrev.web.app :refer
             [wrap-authorize current-user-id active-project]]
            [sysrev.db.core :refer
             [do-query do-execute with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.db.export :as export]
            [sysrev.db.articles :as articles]
            [sysrev.db.documents :as docs]
            [sysrev.db.labels :as labels]
            [sysrev.db.sources :as sources]
            [sysrev.db.files :as files]
            [sysrev.db.article_list :as alist]
            [sysrev.db.annotations :as annotations]
            [sysrev.biosource.importance :as importance]
            [sysrev.export.endnote :as endnote-out]
            [sysrev.files.stores :as fstore]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.predict.report :as predict-report]
            [sysrev.shared.keywords :as keywords]
            [sysrev.shared.transit :as sr-transit]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.config.core :refer [env]]
            [sysrev.util :refer :all]
            [sysrev.shared.util :refer [map-values in? parse-integer to-uuid]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [compojure.core :refer :all]
            [ring.util.response :as response]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure-csv.core :as csv]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.io InputStream]
           [java.io ByteArrayInputStream]
           [org.apache.commons.io IOUtils]))

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
  (let [[article user-labels user-notes article-pdfs]
        (pvalues
         (articles/get-article article-id)
         (labels/article-user-labels-map project-id article-id)
         (articles/article-user-notes-map project-id article-id)
         (api/article-pdfs article-id))]
    {:article (merge (prepare-article-response article)
                     {:pdfs (-> article-pdfs :result :files)})
     :labels user-labels
     :notes user-notes}))

(defn project-info [project-id]
  (with-project-cache
    project-id [:project-info]
    (let [[fields predict articles status-counts members
           users keywords notes files documents progress sources
           importance url-ids]
          (pvalues (q/query-project-by-id project-id [:*])
                   (predict-report/predict-summary
                    (q/project-latest-predict-run-id project-id))
                   (project/project-article-count project-id)
                   (labels/project-article-status-counts project-id)
                   (labels/project-members-info project-id)
                   (project/project-users-info project-id)
                   (project/project-keywords project-id)
                   (project/project-notes project-id)
                   (fstore/project-files project-id)
                   (docs/all-article-document-paths project-id)
                   (labels/query-progress-over-time project-id 30)
                   (sources/project-sources project-id)
                   (:result (api/important-terms project-id))
                   (try
                     (project/project-url-ids project-id)
                     (catch Throwable e
                       (log/info "exception in project-url-ids")
                       [])))]
      {:project {:project-id project-id
                 :name (:name fields)
                 :project-uuid (:project-uuid fields)
                 :members members
                 :stats {:articles articles
                         :status-counts status-counts
                         :predict predict
                         :progress progress}
                 :labels (project/project-labels project-id true)
                 :keywords keywords
                 :notes notes
                 :settings (:settings fields)
                 :files files
                 :documents documents
                 :sources sources
                 :importance importance
                 :url-ids url-ids}
       :users users})))

(defroutes project-routes
  ;; Returns full information for active project
  (GET "/api/project-info" request
       (wrap-authorize
        request {:allow-public true}
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
                (project-info project-id))))))

  (POST "/api/join-project" request
        (wrap-authorize
         request {:logged-in true}
         (let [project-id (-> request :body :project-id)
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
             {:session session}))))

  (POST "/api/create-project" request
        (wrap-authorize
         request {:logged-in true}
         (let [project-name (-> request :body :project-name)
               user-id (current-user-id request)]
           (assert (integer? user-id))
           (api/create-project-for-user! project-name user-id))))

  (POST "/api/delete-project" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (-> request :body :project-id)
               user-id (current-user-id request)]
           (api/delete-project! project-id user-id))))

  (POST "/api/import-articles/pubmed" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [{:keys [search-term source]} (:body request)
               project-id (active-project request)]
           (api/import-articles-from-search
            project-id search-term source
            :threads 3))))

  (POST "/api/import-articles/pmid-file/:project-id" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (active-project request)
               {:keys [tempfile filename]} (get-in request [:params :file])]
           (api/import-articles-from-file
            project-id tempfile filename
            :threads 3))))

  (POST "/api/import-articles/endnote-xml/:project-id" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (active-project request)
               {:keys [tempfile filename]} (get-in request [:params :file])]
           (api/import-articles-from-endnote-file
            project-id tempfile filename))))

  (POST "/api/import-articles/pdf-zip/:project-id" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (active-project request)
               {:keys [tempfile filename]} (get-in request [:params :file])]
           (api/import-articles-from-pdf-zip-file
            tempfile filename project-id
            :threads 3))))

  ;; Returns an article for user to label
  (GET "/api/label-task" request
       (wrap-authorize
        request {:roles ["member"]}
        (update-user-default-project request)
        (if-let [{:keys [article-id today-count] :as task}
                 (labels/get-user-label-task (active-project request)
                                             (current-user-id request))]
          {:result
           (let [project-id (active-project request)
                 result (article-info-full project-id article-id)]
             (merge result
                    {:today-count today-count}))}
          {:result :none})))

  ;; Sets and optionally confirms label values for an article
  (POST "/api/set-labels" request
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
           (labels/set-user-article-labels user-id article-id label-values
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
           {:result body})))

  (POST "/api/set-article-note" request
        (wrap-authorize
         request {:roles ["member"]}
         (let [user-id (current-user-id request)
               {:keys [article-id name content]
                :as body} (-> request :body)]
           (articles/set-user-article-note article-id user-id name content)
           {:result body})))

  (GET "/api/member-articles/:user-id" request
       (wrap-authorize
        request {:allow-public true}
        (let [user-id (-> request :params :user-id Integer/parseInt)
              project-id (active-project request)]
          (update-user-default-project request)
          {:result (sr-transit/encode-member-articles
                    (labels/query-member-articles project-id user-id))})))

  ;; Returns map with full information on an article
  (GET "/api/article-info/:article-id" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (active-project request)
              article-id (-> request :params :article-id Integer/parseInt)]
          (let [{:keys [article] :as result}
                (article-info-full project-id article-id)]
            (when (= (:project-id article) project-id)
              (update-user-default-project request)
              result)))))

  ;; Note that transit-clj is not used with query params.
  ;; Therefore, the string request parameter 'page-number' is converted to an integer
  ;; before being passed to get-query-pmids
  ;;
  ;; Why not just pass the parameters in the body of the request?
  ;; In REST, a GET request should not return a response based on
  ;; the content of request body.
  ;; see: https://stackoverflow.com/questions/978061/http-get-with-request-body

  ;; Return a vector of PMIDs associated with the given search term
  (GET "/api/pubmed/search" request
       (wrap-authorize
        request {}
        (let [{:keys [term page-number]} (-> :params request)]
          (pubmed/get-search-query-response term (Integer/parseInt page-number)))))

  ;; Return article summaries for a list of PMIDs
  (GET "/api/pubmed/summaries" request
       (wrap-authorize
        request {}
        (let [{:keys [pmids]} (-> :params request)]
          (pubmed/get-pmids-summary (mapv #(Integer/parseInt %)
                                          (clojure.string/split pmids #","))))))

  (POST "/api/delete-member-labels" request
        (wrap-authorize
         request {:roles ["member"]
                  :developer true}
         (let [user-id (current-user-id request)
               project-id (active-project request)
               {:keys [verify-user-id]} (:body request)]
           (assert (= user-id verify-user-id) "verify-user-id mismatch")
           (project/delete-member-labels-notes project-id user-id)
           {:result {:success true}})))

  (GET "/api/project-settings" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (active-project request)]
          {:result {:settings (project/project-settings project-id)
                    :project-name (-> (q/select-project-where
                                       [:= :project-id project-id]
                                       [:name])
                                      do-query first :name)}})))

  (POST "/api/change-project-settings" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (active-project request)
               {:keys [changes]} (:body request)]
           (doseq [{:keys [setting value]} changes]
             (project/change-project-setting
              project-id (keyword setting) value))
           {:result
            {:success true
             :settings (project/project-settings project-id)}})))

  (POST "/api/change-project-name" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (active-project request)
               {:keys [project-name]} (:body request)]
           (project/change-project-name project-id project-name)
           {:result
            {:success true
             :project-name project-name}})))

  (GET "/api/project-sources" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (active-project request)]
          (api/project-sources project-id))))

  (POST "/api/delete-source" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [source-id (-> request :body :source-id)
               user-id (current-user-id request)]
           (api/delete-source! source-id))))

  (POST "/api/toggle-source" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [{:keys [source-id enabled?]} (-> request :body)
               user-id (current-user-id request)]
           (api/toggle-source source-id enabled?))))

  (GET "/api/files/:project-id" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (active-project request)
              files (fstore/project-files project-id)]
          {:result (vec files)})))

  (GET "/api/files/:project-id/download/:key/:name" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (active-project request)
              uuid (-> request :params :key (UUID/fromString))
              file-data (fstore/get-file project-id uuid)
              data (slurp-bytes (:filestream file-data))]
          (response/response (ByteArrayInputStream. data)))))

  (POST "/api/files/:project-id/upload" request
        (wrap-authorize
         request {:roles ["member"]}
         (let [project-id (active-project request)
               file-data (get-in request [:params :file])
               file (:tempfile file-data)
               filename (:filename file-data)
               user-id (current-user-id request)]
           (fstore/store-file project-id user-id filename file)
           {:result 1})))

  (POST "/api/files/:project-id/delete/:key" request
        (wrap-authorize
         ;; TODO: This should be file owner or admin?
         request {:roles ["member"]}
         (let [project-id (active-project request)
               key (-> request :params :key)
               deletion (fstore/delete-file project-id (UUID/fromString key))]
           {:result deletion})))

  ;; TODO: fix permissions without breaking download on Safari
  (GET "/api/export-project/:project-id/:filename" request
       (wrap-authorize
        request {:allow-public true}
        (let [filename (-> request :params :filename)
              project-id (-> request :params :project-id Integer/parseInt)
              ;; project-id (active-project request)
              data (json/write-str (export/export-project project-id))]
          (-> (response/response data)
              (response/header
               "Content-Type"
               "application/json; charset=utf-8")
              (response/header
               "Content-Disposition"
               (format "attachment; filename=\"%s\""
                       filename))))))

  ;; TODO: fix permissions without breaking download on Safari
  (GET "/api/export-answers-csv/:project-id/:filename" request
       (wrap-authorize
        request {:allow-public true}
        (let [filename (-> request :params :filename)
              project-id (-> request :params :project-id Integer/parseInt)
              ;; project-id (active-project request)
              data (->> (export/export-project-answers project-id)
                        (csv/write-csv))]
          (-> (response/response data)
              (response/header "Content-Type"
                               "text/csv; charset=utf-8")
              (response/header
               "Content-Disposition"
               (format "attachment; filename=\"%s\""
                       filename))))))

  ;; TODO: fix permissions without breaking download on Safari
  (GET "/api/export-endnote-xml/:project-id/:filename" request
       (wrap-authorize
        request {:allow-public true}
        (let [filename (-> request :params :filename)
              project-id (-> request :params :project-id Integer/parseInt)
              ;; project-id (active-project request)
              file (endnote-out/project-to-endnote-xml project-id :to-file true)]
          (-> (response/response (io/reader file))
              (response/header "Content-Type" "text/xml; charset=utf-8")
              (response/header "Content-Disposition"
                               (format "attachment; filename=\"%s\"" filename))))))

  (GET "/api/public-labels" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (active-project request)
              exclude-hours (if (= :dev (:profile env))
                              nil nil)]
          (update-user-default-project request)
          {:result
           (->> (labels/query-public-article-labels project-id)
                (labels/filter-recent-public-articles project-id exclude-hours)
                (sr-transit/encode-public-labels))})))

  (POST "/api/project-articles" request
        (wrap-authorize
         request {:allow-public true}
         (let [project-id (active-project request)
               exclude-hours (if (= :dev (:profile env))
                               nil nil)
               args (-> request :body)
               n-count (some-> (:n-count args) parse-integer)
               n-offset (some-> (:n-offset args) parse-integer)
               {:keys [sort-by sort-dir]} args
               lookup-count (let [value (:lookup-count args)]
                              (boolean (or (true? value) (= value "true"))))
               text-search
               (when-let [text-search (:text-search args)]
                 (when (not-empty text-search)
                   text-search))
               filters
               (->> [(:filters args)
                     (when text-search
                       [{:text-search text-search}])]
                    (apply concat)
                    (remove nil?)
                    vec)
               query-result
               (alist/query-project-article-list
                project-id (cond-> {}
                             n-count (merge {:n-count n-count})
                             n-offset (merge {:n-offset n-offset})
                             (not-empty filters) (merge {:filters filters})
                             sort-by (merge {:sort-by sort-by})
                             sort-dir (merge {:sort-dir sort-dir})))]
           (update-user-default-project request)
           {:result
            (if lookup-count
              (:total-count query-result)
              (:entries query-result))})))

  (POST "/api/sync-project-labels" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [{:keys [project-id labels]} (:body request)]
           (api/sync-labels project-id labels))))

  (GET "/api/lookup-project-url" request
       (wrap-authorize
        request {}
        {:result
         (when-let [url-id (-> request :params :url-id)]
           (when-let [project-id
                      (try
                        (project/project-id-from-url-id url-id)
                        (catch Throwable e
                          nil))]
             {:project-id project-id}))}))

  (GET "/api/query-register-project" request
       (wrap-authorize
        request {}
        (let [register-hash (-> request :params :register-hash)
              project-id (project/project-id-from-register-hash register-hash)]
          (if (nil? project-id)
            {:result {:project nil}}
            (let [{:keys [name]} (q/query-project-by-id project-id [:name])]
              {:result {:project {:project-id project-id :name name}}})))))

  (POST "/api/payment-method" request
        (wrap-authorize
         request {:logged-in true}
         (let [{:keys [token]} (:body request)
               user-id (current-user-id request)]
           (api/add-payment-method (users/get-user-by-id user-id) token))))

  (GET "/api/plans" request
       (wrap-authorize
        request {}
        (api/plans)))

  (GET "/api/current-plan" request
       (wrap-authorize
        request {:logged-in true}
        (api/get-current-plan (users/get-user-by-id (current-user-id request)))))

  (POST "/api/support-project" request
        (wrap-authorize
         request {:logged-in true}
         (let [{:keys [project-id amount frequency]} (:body request)]
           (api/support-project (users/get-user-by-id (current-user-id request))
                                project-id
                                amount
                                frequency))))

  (POST "/api/paypal/add-funds" request
        (wrap-authorize
         request {:logged-in true}
         (let [{:keys [project-id user-id response]} (:body request)]
           (api/add-funds-paypal project-id user-id response))))

  (GET "/api/user-support-subscriptions" request
       (wrap-authorize
        request {:logged-in true}
        (api/user-support-subscriptions
         (users/get-user-by-id (current-user-id request)))))

  (GET "/api/current-support" request
       (wrap-authorize
        request {:logged-in true}
        (let [{:keys [project-id]} (-> request :params)]
          (api/current-project-support-level
           (users/get-user-by-id (current-user-id request))
           (parse-integer project-id)))))

  (POST "/api/cancel-project-support" request
        (wrap-authorize
         request {:logged-in true}
         (let [{:keys [project-id]} (:body request)]
           (api/cancel-project-support
            (users/get-user-by-id (current-user-id request))
            project-id))))

  (POST "/api/subscribe-plan" request
        (wrap-authorize
         request {:logged-in true}
         (let [{:keys [plan-name]} (:body request)]
           (api/subscribe-to-plan (users/get-user-by-id (current-user-id request))
                                  plan-name))))

  (GET "/api/important-terms" request
       (wrap-authorize
        request {:allow-public true}
        (let [{:keys [n]} (-> request :params)]
          (api/important-terms (active-project request) (parse-integer n)))))

  (GET "/api/prediction-histograms" request
       (wrap-authorize
        request {:allow-public true}
        (let [{:keys [project-id]} (-> request :params)]
          (api/prediction-histogram (parse-integer project-id)))))

  (GET "/api/charts/label-count-data" request
       (wrap-authorize
        request {:allow-public true}
        (api/label-count-data (-> request :params :project-id parse-integer))))

  (GET "/api/open-access/:article-id/availability" [article-id]
       (api/open-access-available? (parse-integer article-id)))

  (GET "/api/open-access/:article-id/view/:key" [article-id key]
       (api/open-access-pdf (parse-integer article-id) key))

  (POST "/api/files/:project-id/article/:article-id/upload-pdf" request
        (wrap-authorize
         request {:roles ["member"]}
         (let [{:keys [article-id]} (:params request)]
           (let [file-data (get-in request [:params :file])
                 file (:tempfile file-data)
                 filename (:filename file-data)]
             (api/save-article-pdf (parse-integer article-id) file filename)))))

  (GET "/api/files/:project-id/article/:article-id/article-pdfs" request
       (wrap-authorize
        request {:roles ["member"]}
        (let [{:keys [article-id]} (:params request)]
          (api/article-pdfs (parse-integer article-id)))))

  (GET "/api/files/:project-id/article/:article-id/download/:key/:filename" request
       (wrap-authorize
        request {:roles ["member"]}
        (let [{:keys [key]} (:params request)]
          (api/get-s3-file key))))

  (GET "/api/files/:project-id/article/:article-id/view/:key/:filename" request
       (wrap-authorize
        request {:roles ["member"]}
        (let [{:keys [key]} (:params request)]
          (api/view-s3-pdf key))))

  (POST "/api/files/:project-id/article/:article-id/delete/:key/:filename" request
        (wrap-authorize
         request {:roles ["member"]}
         (let [{:keys [article-id key filename]} (:params request)]
           (api/dissociate-pdf-article article-id key filename))))

  (POST "/api/change-project-permissions" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (active-project request)
               {:keys [users-map]} (:body request)]
           (api/change-project-permissions project-id users-map))))

  (POST "/api/annotation/create" request
        (wrap-authorize
         request {:roles ["member"]}
         (let [{:keys [context annotation-map]} (-> request :body)
               {:keys [selection annotation semantic-class]} annotation-map
               {:keys [class article-id pdf-key]} context
               user-id (current-user-id request)
               project-id (active-project request)
               result
               (condp = class
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
                      (-> result :result :annotation-id))
             (api/update-annotation!
              (-> result :result :annotation-id)
              annotation semantic-class user-id))
           result)))

  (POST "/api/annotation/update/:annotation-id" request
        (wrap-authorize
         request {:roles ["member"]}
         (let [annotation-id (-> request :params :annotation-id parse-integer)
               {:keys [annotation semantic-class]} (-> request :body)
               user-id (current-user-id request)]
           (api/update-annotation!
            annotation-id annotation semantic-class user-id))))

  (POST "/api/annotation/delete/:annotation-id" request
        (wrap-authorize
         request {:roles ["member"]}
         (let [annotation-id (-> request :params :annotation-id parse-integer)]
           (api/delete-annotation! annotation-id))))

  (GET "/api/annotation/status" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (active-project request)
              user-id (current-user-id request)]
          (api/project-annotation-status project-id :user-id user-id))))

  (GET "/api/annotations/user-defined/:article-id" request
       (let [article-id (-> request :params :article-id parse-integer)]
         (api/user-defined-annotations article-id)))

  (GET "/api/annotations/user-defined/:article-id/pdf/:pdf-key" request
       (let [article-id (-> request :params :article-id parse-integer)
             pdf-key (-> request :params :pdf-key)]
         (api/user-defined-pdf-annotations article-id pdf-key)))

  (GET "/api/annotations/:article-id" request
       (wrap-authorize
        request {:allow-public true}
        (let [article-id (-> request :params :article-id parse-integer)]
          (api/article-abstract-annotations article-id))))

  (GET "/api/public-projects" request
       (wrap-authorize
        request {}
        (api/public-projects)))

  (GET "/api/project-description" request
       (wrap-authorize
        request {:allow-public true}
        (let [project-id (-> request :params :project-id parse-integer)]
          (api/read-project-description project-id))))

  (POST "/api/project-description" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (-> request :body :project-id)
               markdown (-> request :body :markdown)]
           (api/set-project-description! project-id markdown))))

  (POST "/api/project-compensation" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [project-id (-> request :body :project-id)
               rate (-> request :body :rate)]
           (api/create-project-compensation! project-id rate))))

  (GET "/api/project-compensations" request
       (wrap-authorize
        request {:roles ["admin"]}
        (let [project-id (-> request :params :project-id parse-integer)]
          (api/read-project-compensations project-id))))

  (PUT "/api/toggle-compensation-active" request
       (wrap-authorize
        request {:roles ["admin"]}
        (let [{:keys [project-id compensation-id active]} (:body request)]
          (api/toggle-compensation-active! project-id compensation-id active))))

  (GET "/api/get-default-compensation" request
       (wrap-authorize
        request {:roles ["admin"]}
        (let [project-id (-> request :params :project-id parse-integer)]
          (api/get-default-compensation project-id))))

  (PUT "/api/set-default-compensation" request
       (wrap-authorize
        request {:roles ["admin"]}
        (let [{:keys [project-id compensation-id]} (:body request)]
          (api/set-default-compensation! project-id compensation-id))))

  (GET "/api/compensation-owed" request
       (wrap-authorize
        request {:roles ["admin"]}
        (let [project-id (-> request :params :project-id parse-integer)]
          (api/compensation-owed project-id))))

  (GET "/api/project-users-current-compensation" request
       (wrap-authorize
        request {:roles ["admin"]}
        (let [project-id (-> request :params :project-id parse-integer)]
          (api/project-users-current-compensation project-id))))

  (PUT "/api/set-user-compensation" request
       (wrap-authorize
        request {:roles ["admin"]}
        (let [{:keys [project-id user-id compensation-id]} (:body request)]
          (api/set-user-compensation! project-id user-id compensation-id))))

  (GET "/api/project-funds" request
       (wrap-authorize
        request {:roles ["admin"]}
        (api/project-funds (-> request :params :project-id parse-integer))))

  (POST "/api/pay-user" request
        (wrap-authorize
         request {:roles ["admin"]}
         (let [{:keys [project-id user-id compensation admin-fee]} (-> request :body)]
           (api/pay-user! project-id user-id compensation admin-fee))))

  (POST "/api/stripe/finalize-user" request
        (wrap-authorize
         request {:logged-in true}
         (let [{:keys [user-id stripe-code]} (-> request :body)]
           (api/finalize-stripe-user! user-id stripe-code))))

  (GET "/api/stripe/connected/:user-id" request
       (wrap-authorize
        request {:logged-in true}
        (let [user-id (-> request :params :user-id Integer/parseInt)]
          (api/user-has-stripe-account? user-id))))
  ;;  we are still getting sane responses from the server?
  (GET "/api/test" request
       (wrap-authorize
        request {}
        (api/test-response))))
