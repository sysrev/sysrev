(ns sysrev.web.routes.project
  (:require [clojure-csv.core :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET POST PUT]]
            [ring.util.io :as ring-io]
            [ring.util.response :as response]
            [sysrev.api :as api]
            [sysrev.article.assignment :as assign]
            [sysrev.article.core :as article]
            [sysrev.biosource.predict :as predict-api]
            [sysrev.config :refer [env]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.db.core :as db :refer
             [with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.encryption :as enc]
            [sysrev.export.core :as export]
            [sysrev.export.endnote :refer [project-to-endnote-xml]]
            [sysrev.file.article :as article-file]
            [sysrev.file.document :as doc-file]
            [sysrev.file.s3 :as s3-file]
            [sysrev.formats.pubmed :as pubmed]
            [sysrev.gengroup.core :as gengroup]
            [sysrev.group.core :as group]
            [sysrev.label.answer :as answer]
            [sysrev.label.core :as label]
            [sysrev.predict.report :as predict-report]
            [sysrev.project.article-list :as alist]
            [sysrev.project.core :as project]
            [sysrev.project.description
             :refer [read-project-description
                     set-project-description!]]
            [sysrev.project.member :as member]
            [sysrev.project.plan :as pplan]
            [sysrev.project-api.interface :as project-api]
            [sysrev.shared.keywords :as keywords]
            [sysrev.source.core :as source]
            [sysrev.source.files :as files]
            [sysrev.user.interface :as user]
            [sysrev.util :as util :refer [parse-integer]]
            [sysrev.web.app :as app :refer [active-project current-user-id
                                            with-authorize]]
            [sysrev.web.routes.core :refer [setup-local-routes]])
  (:import (java.io File)))

;; for clj-kondo
(declare project-routes dr finalize-routes)

;;;
;;; Functions for project routes
;;;

(def utf8-bom (String. (byte-array (mapv int [239 187 191]))))

(defn write-csv
  "Return a string of `table` in CSV format, with the UTF-8 BOM added for
  Excel.

  See https://www.edmundofuentes.com/blog/2020/06/13/excel-utf8-csv-bom-string/"
  [table & opts]
  (str utf8-bom
       (apply csv/write-csv table opts)))

(defn record-user-project-interaction [request]
  (let [user-id (current-user-id request)
        project-id (active-project request)]
    (when (and user-id project-id)
      (future
        (db/with-transaction
          (try (user/update-member-access-time user-id project-id)
               (catch Exception _
                 (log/info "exception updating project access time"))))))))

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

(defn article-info-full [_sr-context project-id article-id]
  (let [resolve (label/article-resolved-status project-id article-id)
        resolve-labels (label/article-resolved-labels project-id article-id)
        article (article/get-article article-id)]
    {:article (-> article
                  prepare-article-response
                  (assoc :pdfs (:files (api/article-pdfs article-id))
                         :predictions (article/article-predictions article-id)
                         :review-status (label/article-consensus-status project-id article-id)
                         :resolve (merge resolve {:labels resolve-labels})))
     :labels (label/article-user-labels-map article-id)
     :notes (article/article-user-notes-map article-id)}))

(defn parent-project-info
  [project-id]
  (let [parent-project-id (q/find-one :project {:project-id project-id} :parent-project-id)
        project-name (q/find-one :project {:project-id parent-project-id} :name)
        owner (project/get-project-owner parent-project-id)]
    (when parent-project-id
      {:project-id parent-project-id
       :project-name project-name
       :owner-name (:name owner)})))

(defn project-info [sr-context project-id]
  (with-project-cache project-id [:project-info]
    (let [[[fields users labels keywords members predict
            files owner plan subscription-lapsed?]
           [_ [_status-counts progress]]
           [articles sources]
           parent-project-info
           gengroups]
          (pvalues [(q/query-project-by-id project-id [:*])
                    (member/project-users-info project-id)
                    (project/project-labels project-id true)
                    (project/project-keywords project-id)
                    (label/project-members-info project-id)
                    (predict-report/predict-summary
                     (q/project-latest-predict-run-id project-id))
                    (doc-file/list-project-documents project-id)
                    (project/get-project-owner project-id)
                    (pplan/project-owner-plan project-id)
                    (api/subscription-lapsed? project-id)]
                   [(label/query-public-article-labels project-id)
                    [(label/query-progress-over-time project-id 30)]]
                   [(project/project-article-count project-id)
                    (source/project-sources sr-context project-id)]
                   (parent-project-info project-id)
                   (gengroup/read-project-member-gengroups project-id))]
      {:project
       (-> (select-keys fields [:invite-code :name :project-uuid])
           (assoc :files files
                  :gengroups gengroups
                  :keywords keywords
                  :labels labels
                  :members members
                  :owner owner
                  :parent-project parent-project-info
                  :plan plan
                  :project-id project-id
                  :settings (:settings fields)
                  :sources sources
                  :stats {:articles articles
                          :predict predict
                          :progress progress}
                  :subscription-lapsed? subscription-lapsed?))
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
                            :added-time db/sql-now})]
    (swap! project-export-refs update-in [project-id] #(conj % entry))
    entry))

(defn create-export-tempfile [^String content]
  (let [tempfile (util/create-tempfile)]
    (with-open [w (io/writer tempfile)]
      (.write w content))
    tempfile))

(defn project-not-found [project-id]
  {:error {:status 404
           :type :not-found
           :message (format "Project (%s) not found" project-id)}})

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
            (project-not-found project-id)
            (do (record-user-project-interaction request)
                (project-info (:sr-context request) project-id)))))))

(dr (POST "/api/join-project" {:keys [body sr-context] :as request}
      (with-authorize request {:logged-in true}
        (let [{:project/keys [project-id]} (->> body :invite-code
                                                (project/project-from-invite-code sr-context))
              user-id (current-user-id request)
              session (:session request)]
          (cond
            (not project-id)
            (project-not-found project-id)

            (member/project-member project-id user-id)
            {:error {:status 403
                     :message "User is already a member of this project"}}

            :else
            (do (member/add-project-member project-id user-id)
                (with-meta
                  {:result {:project-id project-id}}
                  {:session session})))))))

(dr (POST "/api/remove-user-from-project" request
      (with-authorize request {:roles ["admin"]}
        (let [{:keys [project-id user-id]} (-> request :body)]
          (api/remove-member-from-project! project-id user-id)
          {:success true}))))


(dr (POST "/api/create-project" request
      (with-authorize request {:logged-in true}
        (let [{:keys [project-name public-access]} (-> request :body)
              user-id (current-user-id request)]
          (assert (integer? user-id))
          (api/create-project-for-user!
           (:sr-context request)
           project-name user-id public-access)))))

(dr (POST "/api/clone-project" {:keys [body sr-context] :as request}
      (with-authorize request {:logged-in true}
        (let [user-id (current-user-id request)]
          (assert (integer? user-id))
          (api/clone-project-for-user!
           sr-context
           {:src-project-id (:src-project-id body)
            :user-id user-id})))))

(dr (POST "/api/disable-project" request
      (with-authorize request {:roles ["admin"]}
        (let [project-id (active-project request)
              user-id (current-user-id request)]
          (api/disable-project! project-id user-id)))))

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
                       project-id (parse-long project-url-id)
                       owner (some-> project-id project/get-project-owner)
                       user-id (some-> user-url-id parse-long)
                       org-id (some-> org-url-id parse-long)
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

(dr (GET "/api/invite-code-info" {:keys [params sr-context] :as request}
      (with-authorize request {}
        (let [{:keys [invite-code]} params
              {:keys [org-id type] :as data}
              (enc/try-decrypt-wrapped64 invite-code)]
          ;; (throw (Exception. "invite-code-info test"))
          (if data
            (case type
              "org-invite-hash"
              {:org {:name (group/group-id->name org-id)
                     :org-id org-id}})
            (let [{:project/keys [name project-id]}
                  (project/project-from-invite-code sr-context invite-code)]
              (when project-id
                {:project {:name name :project-id project-id}})))))))

;; Returns map with full information on an article
(dr (GET "/api/article-info/:article-id" {:keys [sr-context] :as request}
      (with-authorize request {:allow-public true}
        (let [project-id (active-project request)
              article-id (-> request :params :article-id parse-integer)
              {:keys [article] :as result}
              (article-info-full sr-context project-id article-id)]
          (when (= (:project-id article) project-id)
            result)))))

(dr (POST "/api/project-articles" {:keys [body sr-context] :as request}
      (with-authorize request {:allow-public true}
        (let [{:keys [filters lookup-count text-search]} body
              filters (->> [filters
                            (when (seq text-search)
                              [{:text-search text-search}])]
                           (apply concat)
                           (remove nil?))
              query-result (-> (select-keys body [:n-count :n-offset :sort-by :sort-dir])
                               (assoc :filters filters)
                               (->> (alist/query-project-article-list
                                     sr-context (active-project request))))]
          {:result (if (some-> lookup-count str (= "true"))
                     (:total-count query-result)
                     (:entries query-result))}))))

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
        (let [{:keys [#_n]} (-> request :params)]
          (api/project-concordance
           (active-project request)
           :keep-resolved (= "true" (get-in request [:params :keep-resolved])))))))

(dr (GET "/api/countgroup" request
      (with-authorize request {:allow-public true}
        (let [{:keys [#_n]} (-> request :params)]
          (api/project-label-count-groups (active-project request))))))

;;;
;;; Article import
;;;

(dr (POST "/api/import-articles/pubmed" request
      (with-authorize request {:roles ["admin"]}
        (let [{:keys [search-term]} (:body request)
              project-id (active-project request)
              user-id (current-user-id request)]
          (api/import-articles-from-search
           (:sr-context request) project-id search-term :user-id user-id)))))

(dr (POST "/api/import-articles/pmid-file/:project-id" request
      (with-authorize request {:roles ["admin"]}
        (let [project-id (active-project request)
              {:keys [tempfile filename]} (get-in request [:params :file])
              user-id (current-user-id request)]
          (api/import-articles-from-file (:sr-context request) project-id tempfile filename :user-id user-id)))))

(dr (POST "/api/import-articles/pdf-zip/:project-id" request
      (with-authorize request {:roles ["admin"]}
        (let [project-id (active-project request)
              {:keys [tempfile filename]} (get-in request [:params :file])
              user-id (current-user-id request)]
          (api/import-articles-from-pdf-zip-file (:sr-context request) project-id tempfile filename :user-id user-id)))))

(dr (POST "/api/import-files/:project-id"
      {:keys [multipart-params sr-context] :as request}
      (with-authorize request {:roles ["admin"]}
        (let [files (or (get multipart-params "files[]")
                        (get multipart-params "file"))]
          (files/import!
           sr-context
           (app/active-project request)
           (if (map? files) [files] files))))))

(dr (POST "/api/import-trials/ctgov" request
      (with-authorize request {:roles ["admin"]}
        (let [{:keys [entity-ids query]} (:body request)
              project-id (active-project request)
              user-id (current-user-id request)]
          (api/import-trials-from-search (:sr-context request) project-id query entity-ids
                                         :user-id user-id)))))

(dr (POST "/api/import-trials/fda-drugs-docs" request
      (with-authorize request {:roles ["admin"]}
        (let [{:keys [entity-ids query]} (:body request)
              project-id (active-project request)
              user-id (current-user-id request)]
          (api/import-trials-from-fda-drugs-docs
           (:sr-context request) project-id query entity-ids
           :user-id user-id)))))

(dr (POST "/api/import-project-articles"
      {:keys [body sr-context] :as request}
      (with-authorize request {:roles ["admin"]}
        (let [{:keys [project-id urls]} body]
          (api/import-project-articles sr-context project-id urls)))))
;;;
;;; Article review
;;;

(dr (GET "/api/label-task" {:keys [sr-context] :as request}
      (with-authorize request {:roles ["member"]}
        (if-let [{:keys [article-id today-count]}
                 (assign/get-user-label-task (active-project request) (current-user-id request))]
          (let [{:keys [article] :as article-info} (article-info-full sr-context (active-project request) article-id)]
            (assign/record-last-assigned article-id)
            {:result
             (-> article-info
                 (assoc-in [:article :gpt-answers] (article/gpt-answers sr-context article))
                 (assoc :today-count today-count))})
          {:result :none}))))

(dr (POST "/api/set-labels" request
      (with-authorize request {:roles ["member"]}
        (let [user-id (current-user-id request)
              project-id (active-project request)
              {:keys [article-id label-values confirm? change? resolve?]
               :as body} (-> request :body)]
          (answer/set-labels {:project-id project-id
                              :user-id user-id
                              :article-id article-id
                              :label-values label-values
                              :confirm? confirm?
                              :change? change?
                              :resolve? resolve?
                              :request request})
          {:result body}))))

(dr (POST "/api/set-article-note" request
      (with-authorize request {:roles ["member"]}
        (let [user-id (current-user-id request)
              {:keys [article-id content]} (:body request)]
          (article/set-user-article-note article-id user-id content)
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
(dr (GET "/api/pubmed/search" {:as request :keys [sr-context]}
      (with-authorize request {}
        (let [{:keys [term page-number]} (-> :params request)]
          (pubmed/get-search-query-response sr-context term (parse-integer page-number))))))

;; Return article summaries for a list of PMIDs
(dr (GET "/api/pubmed/summaries" {:as request :keys [sr-context]}
      (with-authorize request {}
        (let [{:keys [pmids]} (-> :params request)]
          (pubmed/get-pmids-summary sr-context (mapv parse-integer (str/split pmids #",")))))))

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
              {:keys [project-name]} (:body request)
              error (project-api/project-name-error project-name)]
          (if error
            {:error {:message error}}
            (do
              (project/change-project-name project-id project-name)
              {:success true, :project-name project-name}))))))

(dr (POST "/api/change-project-permissions" request
      (with-authorize request {:roles ["admin"]}
        (let [project-id (active-project request)
              {:keys [users-map]} (:body request)]
          (api/change-project-permissions project-id users-map)))))

(dr (POST "/api/sync-project-labels" request
      (with-authorize request {:roles ["admin"]}
        (api/sync-labels
         (active-project request)
         (-> request :body :labels label/sanitize-labels)))))

(dr (POST "/api/get-label-share-code" request
      (with-authorize request {:roles ["admin"]}
        (let [{:keys [label-id]} (:body request)]
          {:success true :share-code (label/get-share-code label-id)}))))

(dr (POST "/api/import-label" request
      (with-authorize request {:roles ["admin"]}
        (let [project-id (active-project request)
              {:keys [share-code]} (:body request)]
          (api/import-label share-code project-id)))))

(dr (POST "/api/detach-label" request
      (with-authorize request {:roles ["admin"]}
        (let [project-id (active-project request)
              {:keys [label-id]} (:body request)]
          (api/detach-label project-id label-id)))))

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
        (api/project-sources (:sr-context request) (active-project request)))))

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

(dr (POST "/api/re-import-source" {:keys [body sr-context] :as request}
      (with-authorize request {:roles ["admin"]}
        (api/re-import-source sr-context (:source-id body)))))

(dr (GET "/api/sources/download/:project-id/:source-id"
      {:keys [params sr-context] :as request}
      (with-authorize request {:allow-public true}
        (let [_project-id (active-project request)
              source-id (parse-integer (:source-id params))
              {:keys [meta]} (source/get-source source-id)
              {:keys [source filename hash]} meta
              {:keys [key]} (source/source-upload-file source-id)
              response-body (if (= source "RIS file")
                              (ds-api/download-file
                               {:filename filename
                                :hash hash})
                              (s3-file/get-file-stream sr-context key :import))]
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

(dr (GET "/api/files/:project-id/download/:s3-id"
      {:keys [params sr-context] :as request}
      (with-authorize request {:allow-public true}
        (let [project-id (active-project request)
              {:keys [s3-id]} params
              {:keys [filename key]} (doc-file/lookup-document-file project-id (parse-long s3-id))]
          (when filename
            (-> (response/response (s3-file/get-file-stream sr-context key :document))
                (response/header "Content-Disposition"
                                 (format "attachment; filename=\"%s\"" filename))))))))

(dr (POST "/api/files/:project-id/upload"
      {:keys [params sr-context] :as request}
      (with-authorize request {:roles ["member"]}
        (let [project-id (active-project request)
              user-id (current-user-id request)
              {:keys [file]} params
              {:keys [tempfile filename]} file]
          (doc-file/save-document-file sr-context project-id user-id filename tempfile)
          {:result 1}))))

(dr (POST "/api/files/:project-id/delete/:s3-id" request
      (with-authorize request {:roles ["member"]}
        (let [project-id (active-project request)
              {:keys [s3-id]} (:params request)]
          {:result (doc-file/mark-document-file-deleted project-id (parse-long s3-id))}))))

;;
;; Project export files
;;

(dr (GET "/api/project-exports" request
      (with-authorize request {:allow-public true}
        (get-project-exports (active-project request)))))

(dr (POST "/api/generate-project-export/:project-id/:export-type"
      {:keys [body params sr-context] :as request}
      (with-authorize request {:allow-public true}
        (let [project-id (active-project request)
              user-id (current-user-id request)
              export-type (-> params :export-type keyword)
              {:keys [filters text-search separator label-id]} body
              text-search (not-empty text-search)
              filters (vec (concat filters (when text-search [{:text-search text-search}])))
              article-ids (when filters
                            (alist/query-project-article-ids {:project-id project-id} filters))
              tempfile (case export-type
                         :user-answers
                         (-> (export/export-user-answers-csv
                              sr-context
                              project-id :article-ids article-ids :separator separator)
                             (write-csv)
                             (create-export-tempfile))
                         :article-answers
                         (-> (export/export-article-answers-csv
                              sr-context
                              project-id :article-ids article-ids :separator separator)
                             (write-csv)
                             (create-export-tempfile))
                         :articles-csv
                         (-> (export/export-articles-csv
                              sr-context
                              project-id :article-ids article-ids :separator separator)
                             (write-csv)
                             (create-export-tempfile))
                         :annotations-csv
                         (-> (export/export-annotations-csv
                              sr-context
                              project-id :article-ids article-ids :separator separator)
                             (write-csv)
                             (create-export-tempfile))
                         :endnote-xml
                         (project-to-endnote-xml
                          project-id :article-ids article-ids :to-file true)
                         :group-label-csv
                         (-> (export/export-group-label-csv sr-context project-id :label-id label-id)
                             (write-csv)
                             (create-export-tempfile))
                         :json
                         (-> (api/project-json project-id)
                             (clojure.data.json/write-str)
                             (create-export-tempfile))
                         :uploaded-article-pdfs-zip
                         (api/project-article-pdfs-zip sr-context project-id))
              {:keys [download-id]
               :as entry} (add-project-export
                           project-id export-type tempfile
                           {:user-id user-id :filters filters :separator separator})
              filename-base (case export-type
                              :user-answers     "UserAnswers"
                              :article-answers    "Answers"
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
          {:entry (-> (select-keys entry [:download-id :export-type :added-time])
                      (assoc :filename filename
                             :url (str/join "/" ["/api/download-project-export" project-id
                                                 (name export-type) download-id
                                                 (str/replace filename "/" "%2F")])))}))))

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
          (cond (empty? filename) (app/make-error-response
                                   api/bad-request :file "No filename given")
                (nil? file) (app/make-error-response
                             api/not-found :file "Export file not found")
                :else (case export-type
                        (:user-answers :article-answers :articles-csv :annotations-csv :group-label-csv)
                        (-> (io/reader file) (app/csv-file-response filename))
                        :endnote-xml
                        (-> (io/reader file) (app/xml-file-response filename))
                        :json (-> (io/reader file) (app/text-file-response filename))
                        :uploaded-article-pdfs-zip (ring-io/piped-input-stream (fn [os] (io/copy file os)))))))))

;; Legacy route for existing API code
(dr (GET "/api/export-user-answers-csv/:project-id/:filename"
      {:as request :keys [sr-context]}
      (with-authorize request {:allow-public true}
        (-> (export/export-user-answers-csv sr-context (active-project request))
            (write-csv)
            (app/csv-file-response (-> request :params :filename))))))

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

(dr (GET "/api/open-access/:article-id/availability"
      {:keys [params sr-context]}
      (api/open-access-available?
       sr-context
       (parse-integer (:article-id params)))))

;; TODO: article-id is ignored; check value or remove
(dr (GET "/api/open-access/:article-id/view/:key"
      {:keys [params sr-context]}
      (->  (s3-file/get-file-stream sr-context (:key params) :pdf)
           response/response
           (response/header "Content-Type" "application/pdf"))))

(dr (POST "/api/files/:project-id/article/:article-id/upload-pdf"
      {:keys [params sr-context] :as request}
      (with-authorize request {:roles ["member"]}
        (let [{:keys [article-id] file-data :file} params
              file (:tempfile file-data)
              filename (:filename file-data)]
          (api/save-article-pdf sr-context (parse-integer article-id) file filename)))))

(dr (GET "/api/files/:project-id/article/:article-id/article-pdfs" request
      (with-authorize request {:roles ["member"]}
        (let [{:keys [article-id]} (:params request)]
          (api/article-pdfs (parse-integer article-id))))))

(dr (GET "/api/files/:project-id/article/:article-id/download/:key"
      {:keys [params sr-context] :as request}
      (with-authorize request {:roles ["member"]}
        (let [key (:key params)
              article-id (parse-integer (-> params :article-id))
              {:keys [filename]} (->> (article-file/get-article-file-maps article-id)
                                      (filter #(= key (str (:key %))))
                                      first)]
          (-> (response/response (s3-file/get-file-stream sr-context key :pdf))
              (response/header "Content-Type" "application/pdf")
              (response/header "Content-Disposition"
                               (format "attachment; filename=\"%s\"" filename)))))))

(dr (GET "/api/files/:project-id/article/:article-id/view/:key"
      {:keys [params sr-context] :as request}
      (with-authorize request {:roles ["member"]}
        (-> (s3-file/get-file-stream sr-context (:key params) :pdf)
            response/response
            (response/header "Content-Type" "application/pdf")))))

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

(dr (POST "/api/record-ui-errors" request
      (api/record-ui-errors! request)
      {:success true}))

(finalize-routes)
