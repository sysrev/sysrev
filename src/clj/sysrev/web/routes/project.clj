(ns sysrev.web.routes.project
  (:require
   [sysrev.api :as api]
   [sysrev.web.app :refer
    [wrap-permissions current-user-id active-project]]
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
   [sysrev.biosource.importance :as importance]
   [sysrev.export.endnote :as endnote-out]
   [sysrev.files.stores :as fstore]
   [sysrev.biosource.predict :as predict-api]
   [sysrev.predict.report :as predict-report]
   [sysrev.shared.keywords :as keywords]
   [sysrev.shared.transit :as sr-transit]
   [sysrev.import.pubmed :as pubmed]
   [sysrev.config.core :refer [env]]
   [sysrev.util :refer [parse-integer]]
   [sysrev.shared.util :refer [map-values in?]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [compojure.core :refer :all]
   [ring.util.response :as response]
   [clojure.data.json :as json]
   [clojure-csv.core :as csv])
  (:import [java.util UUID]
           [java.io InputStream]
           [java.io ByteArrayInputStream]
           [org.apache.commons.io IOUtils]))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing.
  Taken from http://stackoverflow.com/a/26372677"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

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

(defn project-info [project-id]
  (with-project-cache
    project-id [:project-info]
    (let [[fields predict articles status-counts members
           users keywords notes files documents progress sources
           importance]
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
                   (:result (api/important-terms project-id)))]
      {:project {:project-id project-id
                 :name (:name fields)
                 :project-uuid (:project-uuid fields)
                 :members members
                 :stats {:articles articles
                         :status-counts status-counts
                         :predict predict
                         :progress progress}
                 :labels (project/project-labels project-id)
                 :keywords keywords
                 :notes notes
                 :settings (:settings fields)
                 :files files
                 :documents documents
                 :sources sources
                 :importance importance}
       :users users})))

(defroutes project-routes
  ;; Returns full information for active project
  (GET "/api/project-info" request
       (wrap-permissions
        request [] ["member"]
        (project-info (active-project request))))

  (POST "/api/join-project" request
        (wrap-permissions
         request [] []
         (let [project-id (-> request :body :project-id)
               user-id (current-user-id request)
               session (assoc (:session request)
                              :active-project project-id)]
           (assert (nil? (project/project-member project-id user-id))
                   "join-project: User is already a member of this project")
           (project/add-project-member project-id user-id)
           (users/set-user-default-project user-id project-id)
           (with-meta
             {:result {:project-id project-id}}
             {:session session}))))

  (POST "/api/create-project" request
        (wrap-permissions
         request [] []
         (let [project-name (-> request :body :project-name)
               user-id (current-user-id request)]
           (assert (integer? user-id))
           (api/create-project-for-user! project-name user-id))))

  (POST "/api/delete-project" request
        (let [project-id (-> request :body :project-id)
              user-id (current-user-id request)]
          (api/delete-project! project-id user-id)))

  (POST "/api/import-articles-from-search" request
        (wrap-permissions
         request [] ["admin"]
         (let [{:keys [search-term source]} (:body request)
               project-id (active-project request)]
           (api/import-articles-from-search
            project-id search-term source
            :threads 3))))

  (POST "/api/import-articles-from-file" request
        (wrap-permissions
         request [] ["admin"]
         (let [project-id (active-project request)
               file-data (get-in request [:params :file])
               file (:tempfile file-data)
               filename (:filename file-data)
               user-id (current-user-id request)]
           (api/import-articles-from-file
            project-id file filename
            :threads 3))))

  (POST "/api/import-articles-from-endnote-file" request
        (wrap-permissions
         request [] ["admin"]
         (let [project-id (active-project request)
               file-data (get-in request [:params :file])
               file (:tempfile file-data)
               filename (:filename file-data)
               user-id (current-user-id request)]
           (api/import-articles-from-endnote-file
            project-id file filename))))

  ;; Returns an article for user to label
  (GET "/api/label-task" request
       (wrap-permissions
        request [] ["member"]
        (if-let [{:keys [article-id today-count] :as task}
                 (labels/get-user-label-task (active-project request)
                                             (current-user-id request))]
          {:result
           (let [project-id (active-project request)
                 [article user-labels user-notes]
                 (pvalues
                  (articles/query-article-by-id-full article-id)
                  (labels/article-user-labels-map project-id article-id)
                  (articles/article-user-notes-map project-id article-id))]
             {:article (prepare-article-response article)
              :labels user-labels
              :notes user-notes
              :today-count today-count})}
          {:result :none})))

  ;; Sets and optionally confirms label values for an article
  (POST "/api/set-labels" request
        (wrap-permissions
         request [] ["member"]
         (let [user-id (current-user-id request)
               project-id (active-project request)
               before-count (-> (labels/project-article-status-counts project-id)
                                :reviewed)
               {:keys [article-id label-values confirm? change? resolve?]
                :as body} (-> request :body)]
           (assert (or change? resolve?
                       (not (labels/user-article-confirmed? user-id article-id))))
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
        (wrap-permissions
         request [] ["member"]
         (let [user-id (current-user-id request)
               {:keys [article-id name content]
                :as body} (-> request :body)]
           (articles/set-user-article-note article-id user-id name content)
           {:result body})))

  (GET "/api/member-articles/:user-id" request
       (wrap-permissions
        request [] ["member"]
        (let [user-id (-> request :params :user-id Integer/parseInt)
              project-id (active-project request)]
          {:result (sr-transit/encode-member-articles
                    (labels/query-member-articles project-id user-id))})))

  ;; Returns map with full information on an article
  (GET "/api/article-info/:article-id" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              article-id (-> request :params :article-id Integer/parseInt)]
          (let [[article user-labels user-notes]
                (pvalues
                 (articles/query-article-by-id-full article-id)
                 (labels/article-user-labels-map project-id article-id)
                 (articles/article-user-notes-map project-id article-id))]
            {:article (prepare-article-response article)
             :labels user-labels
             :notes user-notes}))))

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
       (wrap-permissions
        request [] []
        (let [{:keys [term page-number]} (-> :params request)]
          (pubmed/get-search-query-response term (Integer/parseInt page-number)))))

  ;; Return article summaries for a list of PMIDs
  (GET "/api/pubmed/summaries" request
       (wrap-permissions
        request [] []
        (let [{:keys [pmids]} (-> :params request)]
          (pubmed/get-pmids-summary (mapv #(Integer/parseInt %)
                                          (clojure.string/split pmids #","))))))

  (POST "/api/delete-member-labels" request
        (wrap-permissions
         request ["admin"] ["member"]
         (let [user-id (current-user-id request)
               project-id (active-project request)
               {:keys [verify-user-id]} (:body request)]
           (assert (= user-id verify-user-id) "verify-user-id mismatch")
           (project/delete-member-labels-notes project-id user-id)
           {:result {:success true}})))

  (GET "/api/project-settings" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)]
          {:result {:settings (project/project-settings project-id)}})))

  (POST "/api/change-project-settings" request
        (wrap-permissions
         request [] ["admin"]
         (let [project-id (active-project request)
               {:keys [changes]} (:body request)]
           (doseq [{:keys [setting value]} changes]
             (project/change-project-setting
              project-id (keyword setting) value))
           {:result
            {:success true
             :settings (project/project-settings project-id)}})))

  (GET "/api/project-sources" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)]
          (api/project-sources project-id))))

  (POST "/api/delete-source" request
        (wrap-permissions
         request [] ["admin"]
         (let [source-id (-> request :body :source-id)
               user-id (current-user-id request)]
           (api/delete-source! source-id))))

  (POST "/api/toggle-source" request
        (wrap-permissions
         request [] ["admin"]
         (let [{:keys [source-id enabled?]} (-> request :body)
               user-id (current-user-id request)]
           (api/toggle-source! source-id enabled?))))

  (POST "/api/files/upload" request
        (wrap-permissions
         request [] ["member"]
         (let [project-id (active-project request)
               file-data (get-in request [:params :file])
               file (:tempfile file-data)
               filename (:filename file-data)
               user-id (current-user-id request)]
           (fstore/store-file project-id user-id filename file)
           {:result 1})))

  (GET "/api/files" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              files (fstore/project-files project-id)]
          {:result (vec files)})))

  (GET "/api/files/:key/:name" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              uuid (-> request :params :key (UUID/fromString))
              file-data (fstore/get-file project-id uuid)
              data (slurp-bytes (:filestream file-data))]
          (response/response (ByteArrayInputStream. data)))))

  ;; TODO: fix permissions without breaking download on Safari
  (GET "/api/export-project/:project-id/:filename" request
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
                      filename)))))

  ;; TODO: fix permissions without breaking download on Safari
  (GET "/api/export-answers-csv/:project-id/:filename" request
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
                      filename)))))

  ;; TODO: fix permissions without breaking download on Safari
  (GET "/api/export-endnote-xml/:project-id/:filename" request
       (let [filename (-> request :params :filename)
             project-id (-> request :params :project-id Integer/parseInt)
             ;; project-id (active-project request)
             data (endnote-out/project-to-endnote-xml project-id)]
         (-> (response/response data)
             (response/header "Content-Type"
                              "text/xml; charset=utf-8")
             (response/header
              "Content-Disposition"
              (format "attachment; filename=\"%s\""
                      filename)))))

  (POST "/api/files/delete/:key" request
        (wrap-permissions
         ;; TODO: This should be file owner or admin?
         request [] ["member"]
         (let [project-id (active-project request)
               key (-> request :params :key)
               deletion (fstore/delete-file project-id (UUID/fromString key))]
           {:result deletion})))

  (GET "/api/public-labels" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              exclude-hours (if (= :dev (:profile env))
                              nil nil)]
          {:result
           (->> (labels/query-public-article-labels project-id)
                (labels/filter-recent-public-articles project-id exclude-hours)
                (sr-transit/encode-public-labels))})))

  (POST "/api/sync-project-labels" request
        (let [{:keys [project-id labels]} (:body request)]
          (wrap-permissions
           request [] ["admin"]
           (api/sync-labels project-id labels))))

  (GET "/api/query-register-project" request
       (let [register-hash (-> request :params :register-hash)
             project-id (project/project-id-from-register-hash register-hash)]
         (if (nil? project-id)
           {:result {:project nil}}
           (let [{:keys [name]} (q/query-project-by-id project-id [:name])]
             {:result {:project {:project-id project-id :name name}}}))))

  (POST "/api/payment-method" request
        (let [{:keys [token]} (:body request)]
          (api/add-payment-method (users/get-user-by-id (current-user-id request)) token)))

  (GET "/api/plans" request
       (api/plans))

  (GET "/api/current-plan" request
       (api/get-current-plan (users/get-user-by-id (current-user-id request))))

  (POST "/api/subscribe-plan" request
        (let [{:keys [plan-name]} (:body request)]
          (api/subscribe-to-plan (users/get-user-by-id (current-user-id request))
                                 plan-name)))

  (GET "/api/important-terms" request
       (let [{:keys [n]} (-> :params request)]
         (api/important-terms (active-project request) (parse-integer n))))

  ;;  we are still getting sane responses from the server?
  (GET "/api/test" request
       (api/test-response)))
