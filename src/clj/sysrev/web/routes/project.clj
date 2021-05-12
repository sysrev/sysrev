(ns sysrev.web.routes.project
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure-csv.core :as csv]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET POST PUT]]
            [ring.util.response :as response]
            [sysrev.api :as api]
            [sysrev.config :refer [env]]
            [sysrev.web.app :as web :refer [with-authorize current-user-id active-project]]
            [sysrev.web.routes.core :refer [setup-local-routes]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :as db :refer
             [with-transaction with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.user.core :as user]
            [sysrev.project.core :as project]
            [sysrev.project.member :as member]
            [sysrev.project.description
             :refer [read-project-description set-project-description!]]
            [sysrev.project.article-list :as alist]
            [sysrev.gengroup.core :as gengroup]
            [sysrev.group.core :as group]
            [sysrev.article.core :as article]
            [sysrev.label.core :as label]
            [sysrev.label.answer :as answer]
            [sysrev.article.assignment :as assign]
            [sysrev.source.core :as source]
            [sysrev.file.s3 :as s3-file]
            [sysrev.file.document :as doc-file]
            [sysrev.file.article :as article-file]
            [sysrev.export.core :as export]
            [sysrev.export.endnote :refer [project-to-endnote-xml]]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.predict.report :as predict-report]
            [sysrev.shared.keywords :as keywords]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.formats.ctgov :as ctgov]
            [sysrev.slack :as slack]
            [sysrev.util :as util :refer [parse-integer]]
            [ring.util.io :as ring-io])
  (:import (java.io File)))

;; for clj-kondo
(declare project-routes dr finalize-routes)

;;;
;;; Functions for project routes
;;;

(defn record-user-project-interaction [request]
  (let [user-id (current-user-id request)
        project-id (active-project request)]
    (when (and user-id project-id)
      (future
        (try (user/update-member-access-time user-id project-id)
             (catch Throwable _
               (log/info "error updating project access time")))))))

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
         [consensus resolve resolve-labels]
         predictions]
        (pvalues (article/get-article article-id)
                 (label/article-user-labels-map article-id)
                 (article/article-user-notes-map project-id article-id)
                 (api/article-pdfs article-id)
                 (list (label/article-consensus-status project-id article-id)
                       (label/article-resolved-status project-id article-id)
                       (label/article-resolved-labels project-id article-id))
                 (article/article-predictions article-id))]
    {:article (merge (prepare-article-response article)
                     {:pdfs (:files article-pdfs)}
                     {:predictions predictions}
                     {:review-status consensus}
                     {:resolve (merge resolve {:labels resolve-labels})})
     :labels user-labels
     :notes user-notes}))

(defn parent-project-info
  [project-id]
  (let [parent-project-id (q/find-one :project {:project-id project-id} :parent-project-id)
        project-name (q/find-one :project {:project-id parent-project-id} :name)
        owner (project/get-project-owner parent-project-id)]
    (when parent-project-id
      {:project-id parent-project-id
       :project-name project-name
       :owner-name (:name owner)})))

(defn project-info [project-id]
  (with-project-cache project-id [:project-info]
    (let [[[fields users labels keywords notes members predict
            url-ids files owner plan subscription-lapsed?]
           [_ [_status-counts progress]]
           [articles sources]
           parent-project-info
           gengroups]
          (pvalues [(q/query-project-by-id project-id [:*])
                    (project/project-users-info project-id)
                    (project/project-labels project-id true)
                    (project/project-keywords project-id)
                    (project/project-notes project-id)
                    (label/project-members-info project-id)
                    (predict-report/predict-summary
                     (q/project-latest-predict-run-id project-id))
                    (try (project/project-url-ids project-id)
                         (catch Throwable _
                           (log/info "exception in project-url-ids")
                           []))
                    (doc-file/list-project-documents project-id)
                    (project/get-project-owner project-id)
                    (api/project-owner-plan project-id)
                    (api/subscription-lapsed? project-id)]
                   [(label/query-public-article-labels project-id)
                    (pvalues nil #_ (label/project-article-status-counts project-id)
                             (label/query-progress-over-time project-id 30))]
                   [(project/project-article-count project-id)
                    (source/project-sources project-id)]
                   (parent-project-info project-id)
                   (gengroup/read-project-member-gengroups project-id))]
      {:project {:project-id project-id
                 :name (:name fields)
                 :project-uuid (:project-uuid fields)
                 :members members
                 :stats {:articles articles
                         #_ :status-counts #_ status-counts
                         :predict predict
                         :progress progress}
                 :labels labels
                 :keywords keywords
                 :notes notes
                 :settings (:settings fields)
                 :files files
                 :sources sources
                 :url-ids url-ids
                 :owner owner
                 :plan plan
                 :subscription-lapsed? subscription-lapsed?
                 :parent-project parent-project-info
                 :gengroups gengroups}
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
  (let [entry (merge extra {:download-id (util/random-id 5)
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
         (with-authorize request {}
           (api/public-projects))))

(dr (GET "/api/project-info" request
         (with-authorize request {:allow-public true
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
               (do (record-user-project-interaction request)
                   (project-info project-id)))))))

(dr (POST "/api/join-project" request
          (with-authorize request {:logged-in true}
            (let [project-id (active-project request)
                  user-id (current-user-id request)
                  session (:session request)]
              (assert (nil? (member/project-member project-id user-id))
                      "join-project: User is already a member of this project")
              (member/add-project-member project-id user-id)
              (with-meta
                {:result {:project-id project-id}}
                {:session session})))))

(dr (POST "/api/create-project" request
          (with-authorize request {:logged-in true}
            (let [{:keys [project-name public-access]} (-> request :body)
                  user-id (current-user-id request)]
              (assert (integer? user-id))
              (api/create-project-for-user! project-name user-id public-access)))))

(dr (POST "/api/clone-project" request
          (with-authorize request {:logged-in true}
            (let [user-id (current-user-id request)
                  {:keys [src-project-id]} (:body request)]
              (assert (integer? user-id))
              (api/clone-project-for-user! {:src-project-id src-project-id
                                            :user-id user-id})))))

(dr (POST "/api/delete-project" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  user-id (current-user-id request)]
              (api/delete-project! project-id user-id)))))

(dr (POST "/api/create-gengroup" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [gengroup-name gengroup-description]} (:body request)]
              (gengroup/create-project-member-gengroup! project-id gengroup-name gengroup-description)
              {:success true
               :message "Group created."}))))

(dr (POST "/api/update-gengroup" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [gengroup-id gengroup-name gengroup-description]} (:body request)]
              (gengroup/update-project-member-gengroup! project-id gengroup-id gengroup-name gengroup-description)
              {:success true
               :message "Group updated."}))))

(dr (POST "/api/delete-gengroup" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [gengroup-id]} (:body request)]
              (gengroup/delete-project-member-gengroup! project-id gengroup-id)
              {:success true
               :message "Group deleted."}))))

(dr (POST "/api/add-member-to-gengroup" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [gengroup-id membership-id]} (:body request)]
              (gengroup/project-member-gengroup-add project-id gengroup-id membership-id)
              {:success true
               :message "Member added to group"}))))

(dr (POST "/api/remove-member-from-gengroup" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [gengroup-id membership-id]} (:body request)]
              (gengroup/project-member-gengroup-remove project-id gengroup-id membership-id)
              {:success true
               :message "Member removed from group"}))))

(dr (GET "/api/lookup-project-url" request
         (with-authorize request {}
           {:result (let [url-id (-> request :params :url-id util/read-transit-str)
                          [project-url-id {:keys [user-url-id org-url-id]}] url-id
                          ;; TODO: lookup project-id from combination of owner/project names
                          project-id (project/project-id-from-url-id project-url-id)
                          owner (some-> project-id project/get-project-owner)
                          user-id (some-> user-url-id user/user-id-from-url-id)
                          org-id (some-> org-url-id group/group-id-from-url-id)
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
         (with-authorize request {}
           (let [register-hash (-> request :params :register-hash)
                 project-id (project/project-id-from-register-hash register-hash)]
             {:project (when project-id
                         (let [{:keys [name]} (q/query-project-by-id project-id [:name])]
                           {:project-id project-id :name name}))}))))

;; Returns map with full information on an article
(dr (GET "/api/article-info/:article-id" request
         (with-authorize request {:allow-public true}
           (let [project-id (active-project request)
                 article-id (-> request :params :article-id parse-integer)
                 {:keys [article] :as result} (article-info-full project-id article-id)]
             (when (= (:project-id article) project-id)
               (record-user-project-interaction request)
               result)))))

(dr (POST "/api/project-articles" request
          (with-authorize request {:allow-public true}
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
              (record-user-project-interaction request)
              {:result (if lookup-count
                         (:total-count query-result)
                         (:entries query-result))}))))

;;;
;;; Overview Charts data
;;;
(dr (GET "/api/review-status" request
         (with-authorize request {:allow-public true}
           (label/project-article-status-counts (active-project request)))))

(dr (GET "/api/important-terms-text" request
      (with-authorize request {:allow-public true}
        (api/project-important-terms-text (active-project request)))))

(dr (GET "/api/prediction-histograms" request
         (with-authorize request {:allow-public true}
           (api/project-prediction-histogram (active-project request)))))

(dr (GET "/api/charts/label-count-data" request
         (with-authorize request {:allow-public true}
           (api/label-count-chart-data (active-project request)))))

;;;
;;; Analytics Charts data
;;;

(dr (GET "/api/concordance" request
         (with-authorize request {:allow-public true}
           (let [{:keys [#_ n]} (-> request :params)]
             (api/project-concordance
               (active-project request)
               :keep-resolved (= "true" (get-in request [:params :keep-resolved])))))))

(dr (GET "/api/countgroup" request
         (with-authorize request {:allow-public true}
           (let [{:keys [#_ n]} (-> request :params)]
             (api/project-label-count-groups (active-project request))))))

;;;
;;; Article import
;;;

(dr (POST "/api/import-articles/pubmed" request
          (with-authorize request {:roles ["admin"]}
            (let [{:keys [search-term]} (:body request)
                  project-id (active-project request)
                  user-id (current-user-id request)]
              (api/import-articles-from-search project-id search-term :user-id user-id)))))

(dr (POST "/api/import-articles/pmid-file/:project-id" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [tempfile filename]} (get-in request [:params :file])
                  user-id (current-user-id request)]
              (api/import-articles-from-file project-id tempfile filename :user-id user-id)))))

(dr (POST "/api/import-articles/endnote-xml/:project-id" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [tempfile filename]} (get-in request [:params :file])
                  user-id (current-user-id request)]
              (api/import-articles-from-endnote-file project-id tempfile filename :user-id user-id)))))

(dr (POST "/api/import-articles/pdf-zip/:project-id" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [tempfile filename]} (get-in request [:params :file])
                  user-id (current-user-id request)]
              (api/import-articles-from-pdf-zip-file project-id tempfile filename :user-id user-id)))))

(dr (POST "/api/import-articles/json/:project-id" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [tempfile filename]} (get-in request [:params :file])
                  user-id (current-user-id request)]
              (api/import-articles-from-json-file project-id tempfile filename :user-id user-id)))))

(dr (POST "/api/import-articles/pdfs/:project-id" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  user-id (current-user-id request)]
              (api/import-articles-from-pdfs project-id (:multipart-params request) :user-id user-id)))))

(dr (POST "/api/import-articles/ris/:project-id" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [tempfile filename]} (get-in request [:params :file])
                  user-id (current-user-id request)]
              (api/import-articles-from-ris-file project-id tempfile filename :user-id user-id)))))

(dr (POST "/api/import-trials/ctgov" request
          (with-authorize request {:roles ["admin"]}
            (let [{:keys [search-term]} (:body request)
                  project-id (active-project request)
                  user-id (current-user-id request)]
              (api/import-trials-from-search project-id search-term :user-id user-id)))))
;;;
;;; Article review
;;;

(dr (GET "/api/label-task" request
         (with-authorize request {:roles ["member"]}
           (record-user-project-interaction request)
           (if-let [{:keys [article-id today-count]}
                    (assign/get-user-label-task (active-project request) (current-user-id request))]
             (do
               (assign/record-last-assigned article-id)
               {:result (merge (article-info-full (active-project request) article-id)
                             {:today-count today-count})})
             {:result :none}))))

;; Sets and optionally confirms label values for an article
(def exponential-steps (into [15] (->> (range 0 20) (map #(Math/pow 1.7 %)) (filter #(>= % 30)) (map int))))

(dr (POST "/api/set-labels" request
          (with-authorize request {:roles ["member"]}
            (let [user-id (current-user-id request)
                  project-id (active-project request)
                  before-count (label/count-reviewed-articles project-id)
                  {:keys [article-id label-values confirm? change? resolve?]
                   :as body} (-> request :body)
                  duplicate-save? (and (label/user-article-confirmed? user-id article-id)
                                       (not change?)
                                       (not resolve?))]
              (record-user-project-interaction request)
              (if duplicate-save?
                (do (log/warnf "api/set-labels: answer already confirmed ; %s" (pr-str body))
                    (slack/try-log-slack
                     [(format "*Request*:\n```%s```"
                              (util/pp-str (slack/request-info request)))]
                     "Duplicate /api/set-labels request"))
                (answer/set-user-article-labels user-id article-id label-values
                                                :imported? false
                                                :confirm? confirm?
                                                :change? change?
                                                :resolve? resolve?))
              (let [after-count (label/count-reviewed-articles project-id)]
                (when (and (> after-count before-count)
                           (not= 0 after-count)
                           (seq (filter #(= % after-count) exponential-steps)))
                  (predict-api/schedule-predict-update project-id)))
              {:result body}))))

(dr (POST "/api/set-article-note" request
          (with-authorize request {:roles ["member"]}
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
         (with-authorize request {}
           (let [{:keys [term page-number]} (-> :params request)]
             (pubmed/get-search-query-response term (parse-integer page-number))))))

;; Return article summaries for a list of PMIDs
(dr (GET "/api/pubmed/summaries" request
         (with-authorize request {}
           (let [{:keys [pmids]} (-> :params request)]
             (pubmed/get-pmids-summary (mapv parse-integer (str/split pmids #",")))))))

(dr (GET "/api/ctgov/search" request
         (with-authorize request {}
           (let [{:keys [term page-number]} (-> :params request)]
             (ctgov/search term (parse-integer page-number))))))
;;;
;;; Project settings
;;;

(dr (GET "/api/project-settings" request
         (with-authorize request {:allow-public true
                                  :bypass-subscription-lapsed? true}
           (let [project-id (active-project request)]
             {:settings (project/project-settings project-id)
              :project-name (q/find-one :project {:project-id project-id} :name)}))))

(dr (POST "/api/change-project-settings" request
          (with-authorize request {:roles ["admin"]
                                   :bypass-subscription-lapsed? true}
            (let [project-id (active-project request)
                  {:keys [changes]} (:body request)]
              (api/change-project-settings project-id changes)))))

(dr (POST "/api/change-project-name" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [project-name]} (:body request)]
              (project/change-project-name project-id project-name)
              {:success true, :project-name project-name}))))

(dr (POST "/api/change-project-permissions" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [users-map]} (:body request)]
              (api/change-project-permissions project-id users-map)))))

(dr (POST "/api/sync-project-labels" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [labels]} (:body request)]
              (api/sync-labels project-id labels)))))

(dr (GET "/api/project-description" request
         (with-authorize request {:allow-public true}
           (let [project-id (active-project request)]
             {:project-description (read-project-description project-id)}))))

(dr (POST "/api/project-description" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [markdown]} (:body request)]
              (set-project-description! project-id markdown)
              {:project-description markdown}))))

(dr (POST "/api/send-project-invites" request
          (with-authorize request {:roles ["admin"]}
            (let [max-bulk-invitations (:max-bulk-invitations env)
                  project-id (active-project request)
                  {:keys [emails]} (-> request :body)
                  unique-emails (set emails)
                  unique-emails-count (count unique-emails)]
              (cond
                (zero? unique-emails-count)
                {:error {:status api/bad-request
                         :message (str "At least 1 email is required")}}
                (> unique-emails-count max-bulk-invitations)
                {:error {:status api/bad-request
                         :message (str "Maximum emails allowed are " max-bulk-invitations)}}
                :else
                (let [response (api/send-bulk-invitations project-id unique-emails)]
                  (if (:success response)
                    response
                    {:error {:status api/bad-request
                             :message (:message response)}})))))))

;;;
;;; Project sources
;;;

(dr (GET "/api/project-sources" request
         (with-authorize request {:allow-public true}
           (api/project-sources (active-project request)))))

(dr (POST "/api/delete-source" request
          (with-authorize request {:roles ["admin"]}
            (let [source-id (-> request :body :source-id)
                  _user-id (current-user-id request)]
              (api/delete-source! source-id)))))

(dr (POST "/api/toggle-source" request
          (with-authorize request {:roles ["admin"]}
            (let [{:keys [source-id enabled?]} (-> request :body)
                  _user-id (current-user-id request)]
              (api/toggle-source source-id enabled?)))))

(dr (POST "/api/update-source" request
          (with-authorize request {:roles ["admin"]}
            (let [{:keys [source-id check-new-results? import-new-results? notes]} (-> request :body)
                  _user-id (current-user-id request)]
              (api/update-source source-id check-new-results? import-new-results? notes)))))

(dr (POST "/api/re-import-source" request
          (with-authorize request {:roles ["admin"]}
            (let [{:keys [source-id]} (-> request :body)
                  _user-id (current-user-id request)]
              (api/re-import-source source-id)))))

(dr (GET "/api/sources/download/:project-id/:source-id" request
         (with-authorize request {:allow-public true}
           (let [_project-id (active-project request)
                 source-id (parse-integer (-> request :params :source-id))
                 {:keys [meta]} (source/get-source source-id)
                 {:keys [source filename hash]} meta
                 {:keys [key]} (source/source-upload-file source-id)
                 response-body (if (= source "RIS file")
                                 (ds-api/download-file
                                  {:filename filename
                                   :hash hash})
                                 (s3-file/get-file-stream key :import))]
             (-> (response/response response-body)
                 (response/header "Content-Disposition"
                                  (format "attachment; filename=\"%s\"" filename)))))))

;; admin request because it could potentially expose
;; sensitive information
(dr (GET "/api/sources/:source-id/sample-article" request
         (with-authorize request {:roles ["admin"]}
           (let [source-id (parse-integer (-> request :params :source-id))]
             (api/source-sample source-id)))))

(dr (POST "/api/sources/:source-id/cursors" request
          (with-authorize request {:roles ["admin"]}
            (let [source-id (parse-integer (-> request :params :source-id))
                  {:keys [cursors]} (-> request :body)]
              (api/update-source-cursors! source-id cursors)))))

;;;
;;; Project document files
;;;

(dr (GET "/api/files/:project-id" request
         (with-authorize request {:allow-public true}
           (let [project-id (active-project request)
                 files (doc-file/list-project-documents project-id)]
             {:result (vec files)}))))

(dr (GET "/api/files/:project-id/download/:file-key" request
         (with-authorize request {:allow-public true}
           (let [project-id (active-project request)
                 {:keys [file-key]} (:params request)
                 {:keys [filename]} (doc-file/lookup-document-file project-id file-key)]
             (when filename
               (-> (response/response (s3-file/get-file-stream file-key :document))
                   (response/header "Content-Disposition"
                                    (format "attachment; filename=\"%s\"" filename))))))))

(dr (POST "/api/files/:project-id/upload" request
          (with-authorize request {:roles ["member"]}
            (let [project-id (active-project request)
                  user-id (current-user-id request)
                  {:keys [file]} (:params request)
                  {:keys [tempfile filename]} file]
              (doc-file/save-document-file project-id user-id filename tempfile)
              {:result 1}))))

(dr (POST "/api/files/:project-id/delete/:file-key" request
          (with-authorize request {:roles ["member"]}
            (let [project-id (active-project request)
                  {:keys [file-key]} (:params request)]
              {:result (doc-file/mark-document-file-deleted project-id file-key)}))))

;;
;; Project export files
;;

(dr (GET "/api/project-exports" request
         (with-authorize request {:allow-public true}
           (get-project-exports (active-project request)))))

(dr (POST "/api/generate-project-export/:project-id/:export-type" request
          (with-authorize request {:allow-public true}
            (let [project-id (active-project request)
                  user-id (current-user-id request)
                  export-type (-> request :params :export-type keyword)
                  {:keys [filters text-search separator label-id]} (:body request)
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
                              project-id :article-ids article-ids :to-file true)
                             :group-label-csv
                             (-> (export/export-group-label-csv project-id :label-id label-id)
                                 (csv/write-csv)
                                 (create-export-tempfile))
                             :json
                             (-> (api/project-json project-id)
                                 (clojure.data.json/write-str)
                                 (create-export-tempfile))
                             :uploaded-article-pdfs-zip
                             (api/project-article-pdfs-zip project-id))
                  {:keys [download-id]
                   :as entry} (add-project-export
                               project-id export-type tempfile
                               {:user-id user-id :filters filters :separator separator})
                  filename-base (case export-type
                                  :user-answers     "UserAnswers"
                                  :group-answers    "Answers"
                                  :endnote-xml      "Articles"
                                  :articles-csv     "Articles"
                                  :annotations-csv  "Annotations"
                                  :group-label-csv  "GroupLabel"
                                  :uploaded-article-pdfs-zip "UPLOADED_PDFS"
                                  :json             "JSON"
                                  )
                  filename-ext (case export-type
                                 (:user-answers
                                  :group-answers
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
              {:entry (-> (select-keys entry [:download-id :export-type :added-time])
                          (assoc :filename filename
                                 :url (str/join "/" ["/api/download-project-export" project-id
                                                     (name export-type) download-id filename])))}))))

(dr (GET "/api/download-project-export/:project-id/:export-type/:download-id/:filename" request
      (with-authorize request {:allow-public true}
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
                           (:user-answers :group-answers :articles-csv :annotations-csv :group-label-csv)
                           (-> (io/reader file) (web/csv-file-response filename))
                           :endnote-xml
                           (-> (io/reader file) (web/xml-file-response filename))
                           :json (-> (io/reader file) (web/text-file-response filename))
                           :uploaded-article-pdfs-zip (ring-io/piped-input-stream (fn [os] (io/copy file os)))))))))

;; Legacy route for existing API code
(dr (GET "/api/export-user-answers-csv/:project-id/:filename" request
         (with-authorize request {:allow-public true}
           (-> (export/export-user-answers-csv (active-project request))
               (csv/write-csv)
               (web/csv-file-response (-> request :params :filename))))))

;;;
;;; Project support subscriptions
;;;

(dr (GET "/api/user-support-subscriptions" request
         (with-authorize request {:logged-in true}
           (api/user-support-subscriptions
            (q/get-user (current-user-id request))))))

(dr (GET "/api/current-support" request
         (with-authorize request {:logged-in true}
           (api/user-project-support-level
            (q/get-user (current-user-id request))
            (active-project request)))))

(dr (POST "/api/cancel-project-support" request
          (with-authorize request {:logged-in true}
            (api/cancel-user-project-support
             (q/get-user (current-user-id request))
             (active-project request)))))

;;;
;;; PDF files
;;;

(dr (GET "/api/open-access/:article-id/availability" [article-id]
         (api/open-access-available? (parse-integer article-id))))

;; TODO: article-id is ignored; check value or remove
(dr (GET "/api/open-access/:article-id/view/:key" [_article-id key]
         (-> (response/response (s3-file/get-file-stream key :pdf))
             (response/header "Content-Type" "application/pdf"))))

(dr (POST "/api/files/:project-id/article/:article-id/upload-pdf" request
          (with-authorize request {:roles ["member"]}
            (let [{:keys [article-id]} (:params request)
                  file-data (get-in request [:params :file])
                  file (:tempfile file-data)
                  filename (:filename file-data)]
              (api/save-article-pdf (parse-integer article-id) file filename)))))

(dr (GET "/api/files/:project-id/article/:article-id/article-pdfs" request
         (with-authorize request {:roles ["member"]}
           (let [{:keys [article-id]} (:params request)]
             (api/article-pdfs (parse-integer article-id))))))

(dr (GET "/api/files/:project-id/article/:article-id/download/:key" request
         (with-authorize request {:roles ["member"]}
           (let [key (-> request :params :key)
                 article-id (parse-integer (-> request :params :article-id))
                 {:keys [filename]} (->> (article-file/get-article-file-maps article-id)
                                         (filter #(= key (str (:key %))))
                                         first)]
             (-> (response/response (s3-file/get-file-stream key :pdf))
                 (response/header "Content-Type" "application/pdf")
                 (response/header "Content-Disposition"
                                  (format "attachment; filename=\"%s\"" filename)))))))

(dr (GET "/api/files/:project-id/article/:article-id/view/:key" request
         (with-authorize request {:roles ["member"]}
           (let [{:keys [key]} (:params request)]
             (-> (response/response (s3-file/get-file-stream key :pdf))
                 (response/header "Content-Type" "application/pdf"))))))

(dr (POST "/api/files/:project-id/article/:article-id/delete/:key" request
          (with-authorize request {:roles ["admin"]}
            (let [key (-> request :params :key)
                  article-id (parse-integer (-> request :params :article-id))
                  {:keys [filename]} (->> (article-file/get-article-file-maps article-id)
                                          (filter #(= key (str (:key %))))
                                          first)]
              (if (not= (q/get-article article-id :project-id)
                        (active-project request))
                {:error {:status api/not-found
                         :message (str "Article " article-id " not found in project")}}
                (api/dissociate-article-pdf article-id key filename))))))

;;;
;;; Article annotations
;;;

(dr (POST "/api/annotation/create" request
          (with-authorize request {:roles ["member"]}
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
          (with-authorize request {:roles ["member"]}
            (let [annotation-id (-> request :params :annotation-id parse-integer)
                  {:keys [annotation semantic-class]} (-> request :body)
                  user-id (current-user-id request)]
              (api/update-annotation!
               annotation-id annotation semantic-class user-id)))))

(dr (POST "/api/annotation/delete/:annotation-id" request
          (with-authorize request {:roles ["member"]}
            (let [annotation-id (-> request :params :annotation-id parse-integer)]
              (api/delete-annotation! annotation-id)))))

(dr (GET "/api/annotation/status" request
         (with-authorize request {:allow-public true}
           (let [project-id (active-project request)
                 user-id (current-user-id request)]
             (api/project-annotation-status project-id :user-id user-id)))))

(dr (GET "/api/annotations/user-defined/:article-id" request
         (let [article-id (-> request :params :article-id parse-integer)]
           (api/article-user-annotations article-id))))

(dr (GET "/api/annotations/user-defined/:article-id/pdf/:pdf-key" request
         (let [article-id (-> request :params :article-id parse-integer)
               pdf-key (-> request :params :pdf-key)]
           (api/article-pdf-user-annotations article-id pdf-key))))

;; unused
(dr (GET "/api/annotations/:article-id" request
         (with-authorize request {:allow-public true}
           (let [article-id (-> request :params :article-id parse-integer)]
             (api/article-abstract-annotations article-id)))))

;;;
;;; Funding and compensation
;;;

(dr (POST "/api/paypal/add-funds" request
          (with-authorize request {:logged-in true}
            (let [project-id (active-project request)
                  {:keys [user-id order-id]} (:body request)]
              (api/add-funds-paypal project-id user-id order-id)))))

(dr (POST "/api/project-compensation" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [rate]} (:body request)]
              (api/create-project-compensation! project-id rate)))))

(dr (GET "/api/project-compensations" request
         (with-authorize request {:roles ["admin"]}
           (let [project-id (active-project request)]
             (api/read-project-compensations project-id)))))

(dr (PUT "/api/toggle-compensation-enabled" request
         (with-authorize request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [compensation-id enabled]} (:body request)]
             (api/toggle-compensation-enabled! project-id compensation-id enabled)))))

(dr (GET "/api/get-default-compensation" request
         (with-authorize request {:roles ["admin"]}
           (api/get-default-compensation (active-project request)))))

(dr (PUT "/api/set-default-compensation" request
         (with-authorize request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [compensation-id]} (:body request)]
             (api/set-default-compensation! project-id compensation-id)))))

(dr (GET "/api/compensation-owed" request
         (with-authorize request {:roles ["admin"]}
           (api/compensation-owed (active-project request)))))

(dr (GET "/api/project-users-current-compensation" request
         (with-authorize request {:roles ["admin"]}
           (api/project-users-current-compensation (active-project request)))))

(dr (PUT "/api/set-user-compensation" request
         (with-authorize request {:roles ["admin"]}
           (let [project-id (active-project request)
                 {:keys [user-id compensation-id]} (:body request)]
             (api/set-user-compensation! project-id user-id compensation-id)))))

(dr (GET "/api/project-funds" request
         (with-authorize request {:roles ["admin"]}
           (api/project-funds (active-project request)))))

(dr (PUT "/api/check-pending-transaction" request
         (with-authorize request {:roles ["admin"]}
           (api/check-pending-project-transactions! (active-project request)))))

(dr (POST "/api/pay-user" request
          (with-authorize request {:roles ["admin"]}
            (let [project-id (active-project request)
                  {:keys [user-id compensation admin-fee]} (-> request :body)]
              (api/pay-user! project-id user-id compensation admin-fee)))))

;;;
;;; Developer-only requests
;;;

(dr (POST "/api/delete-member-labels" request
          (with-authorize request {:roles ["member"]
                                   :developer true}
            (let [user-id (current-user-id request)
                  project-id (active-project request)
                  {:keys [verify-user-id]} (:body request)]
              (assert (= user-id verify-user-id) "verify-user-id mismatch")
              (project/delete-member-labels-notes project-id user-id)
              {:success true}))))

(dr (POST "/api/update-project-predictions" request
          (with-authorize request {:developer true}
            (let [project-id (active-project request)]
              (future (predict-api/update-project-predictions project-id))
              {:success true}))))

(finalize-routes)
