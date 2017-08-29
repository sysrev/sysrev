(ns sysrev.web.routes.project
  (:require
   [sysrev.web.app :refer [wrap-permissions current-user-id active-project]]
   [sysrev.db.core :refer
    [do-query do-execute with-project-cache]]
   [sysrev.db.queries :as q]
   [sysrev.db.users :as users]
   [sysrev.db.project :as project :refer
    [project-labels project-member project-article-count project-keywords
     project-notes project-settings]]
   [sysrev.db.export :refer [export-project]]
   [sysrev.db.articles :as articles]
   [sysrev.db.documents :as docs]
   [sysrev.db.labels :as labels]
   [sysrev.files.stores :refer [store-file project-files delete-file get-file]]
   [sysrev.predict.report :refer [predict-summary]]
   [sysrev.shared.article-list :refer
    [is-resolved? is-conflict? is-single? is-consistent?]]
   [sysrev.shared.util :refer [map-values in?]]
   [sysrev.shared.keywords :refer [process-keywords format-abstract]]
   [sysrev.shared.transit :as sr-transit]
   [sysrev.util :refer
    [should-never-happen-exception integerify-map-keys uuidify-map-keys]]
   [sysrev.config.core :refer [env]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [compojure.core :refer :all]
   [ring.util.response :as response]
   [clojure.data.json :as json])
  (:import [java.util UUID]
           [java.io InputStream]
           [java.io ByteArrayInputStream]
           [org.apache.commons.io IOUtils]))

(declare project-info
         prepare-article-response)

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing.
  Taken from http://stackoverflow.com/a/26372677"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

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

  ;; Returns web file paths for all local article PDF documents
  (GET "/api/article-documents" request
       (wrap-permissions
        request [] ["member"]
        (docs/all-article-document-paths)))

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
               {:keys [article-id label-values confirm? change? resolve?]
                :as body} (-> request :body)]
           (assert (or change? resolve?
                       (not (labels/user-article-confirmed? user-id article-id))))
           (labels/set-user-article-labels user-id article-id label-values
                                           :imported? false
                                           :confirm? confirm?
                                           :change? change?
                                           :resolve? resolve?)
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
          {:result {:settings (project-settings project-id)}})))

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
             :settings (project-settings project-id)}})))

  (POST "/api/files/upload" request
        (wrap-permissions
         request [] ["member"]
         (let [project-id (active-project request)
               file-data (get-in request [:params :file])
               file (:tempfile file-data)
               filename (:filename file-data)
               user-id (current-user-id request)]
           (store-file project-id user-id filename file)
           {:result 1})))

  (GET "/api/files" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              files (project-files project-id)]
          {:result (vec files)})))

  (GET "/api/files/:key/:name" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              uuid (-> request :params :key (UUID/fromString))
              file-data (get-file project-id uuid)
              data (slurp-bytes (:filestream file-data))]
          (response/response (ByteArrayInputStream. data)))))

  (GET "/api/export-project" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              data (json/write-str (export-project project-id))]
          (-> (response/response data)
              (response/header "Content-Type"
                               "application/json; charset=utf-8")))))

  (POST "/api/files/delete/:key" request
        (wrap-permissions
                                        ;TODO: This should be file owner or admin?
         request [] ["member"]
         (let [project-id (active-project request)
               key (-> request :params :key)
               deletion (delete-file project-id (UUID/fromString key))]
           {:result deletion})))

  (GET "/api/public-labels" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              exclude-hours (if (= :dev (:profile env))
                              nil 4)]
          {:result
           (-> (labels/query-public-article-labels project-id)
               (labels/filter-recent-public-articles exclude-hours)
               (sr-transit/encode-public-labels))})))

  (GET "/api/query-register-project" request
       (let [register-hash (-> request :params :register-hash)
             project-id (project/project-id-from-register-hash register-hash)]
         (if (nil? project-id)
           {:result {:project nil}}
           (let [{:keys [name]} (q/query-project-by-id project-id [:name])]
             {:result {:project {:project-id project-id :name name}}})))))

(defn prepare-article-response
  [{:keys [abstract primary-title secondary-title] :as article}]
  (let [keywords (project/project-keywords (:project-id article))]
    (cond-> article
      true (dissoc :raw)
      abstract
      (assoc :abstract-render
             (format-abstract abstract keywords))
      primary-title
      (assoc :title-render
             (process-keywords primary-title keywords))
      secondary-title
      (assoc :journal-render
             (process-keywords secondary-title keywords)))))

(defn project-include-labels [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-labels]
    (->>
     (-> (q/select-project-article-labels
          project-id true
          [:a.article-id :al.user-id :al.answer :al.resolve])
         (q/filter-overall-label)
         do-query)
     (group-by :article-id))))

(defn project-include-label-conflicts [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-label-conflicts]
    (->> (project-include-labels project-id)
         (filter (fn [[aid labels]]
                   (< 1 (->> labels (map :answer) distinct count))))
         (filter (fn [[aid labels]]
                   (= 0 (->> labels (filter :resolve) count))))
         (apply concat)
         (apply hash-map))))

(defn project-include-label-resolved [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-label-resolved]
    (->> (project-include-labels project-id)
         (filter (fn [[aid labels]]
                   (not= 0 (->> labels (filter :resolve) count))))
         (apply concat)
         (apply hash-map))))

(defn project-user-inclusions [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :user-inclusions]
    (->>
     (-> (q/select-project-article-labels
          project-id true [:al.article-id :user-id :answer])
         (q/filter-overall-label)
         do-query)
     (group-by :user-id)
     (mapv (fn [[user-id entries]]
             (let [includes
                   (->> entries
                        (filter (comp true? :answer))
                        (mapv :article-id))
                   excludes
                   (->> entries
                        (filter (comp false? :answer))
                        (mapv :article-id))]
               [user-id {:includes includes
                         :excludes excludes}])))
     (apply concat)
     (apply hash-map))))

(defn project-members-info [project-id]
  (with-project-cache
    project-id [:members-info]
    (let [users (->> (-> (q/select-project-members
                          project-id [:u.* [:m.permissions :project-permissions]])
                         do-query)
                     (group-by :user-id)
                     (map-values first))
          inclusions (project-user-inclusions project-id)
          in-progress
          (->> (-> (q/select-project-articles
                    project-id [:al.user-id :%count.%distinct.al.article-id])
                   (q/join-article-labels)
                   (q/filter-valid-article-label false)
                   (group :al.user-id)
                   do-query)
               (group-by :user-id)
               (map-values (comp :count first)))]
      (->> users
           (mapv (fn [[user-id user]]
                   [user-id
                    {:permissions (:project-permissions user)
                     :articles (get inclusions user-id)
                     :in-progress (if-let [count (get in-progress user-id)]
                                    count 0)}]))
           (apply concat)
           (apply hash-map)))))

(defn project-users-info [project-id]
  (with-project-cache
    project-id [:users-info]
    (->> (-> (q/select-project-members project-id [:u.*])
             do-query)
         (group-by :user-id)
         (map-values first)
         (map-values
          #(select-keys % [:user-id :user-uuid :email :verified :permissions])))))

(defn project-article-status-counts [project-id]
  (with-project-cache
    project-id [:public-labels :status-counts]
    (let [articles (labels/query-public-article-labels project-id)
          overall-id (project/project-overall-label-id project-id)]
      (when overall-id
        (let [status-vals
              (->> (vals articles)
                   (map
                    (fn [article]
                      (let [labels (get-in article [:labels overall-id])
                            group-status
                            (cond (is-single? labels)     :single
                                  (is-resolved? labels)   :resolved
                                  (is-conflict? labels)   :conflict
                                  :else                   :consistent)
                            inclusion-status
                            (case group-status
                              :conflict nil,
                              :resolved (->> labels (filter :resolve) (map :inclusion) first),
                              (->> labels (map :inclusion) first))]
                        [group-status inclusion-status]))))]
          (merge
           {:reviewed (count articles)}
           (->> (distinct status-vals)
                (map (fn [status]
                       [status (->> status-vals (filter (partial = status)) count)]))
                (apply concat)
                (apply hash-map))))))))

(defn project-info [project-id]
  (let [[fields predict articles status-counts members
         users keywords notes settings files]
        (pvalues (q/query-project-by-id project-id [:*])
                 (predict-summary (q/project-latest-predict-run-id project-id))
                 (project-article-count project-id)
                 (project-article-status-counts project-id)
                 (project-members-info project-id)
                 (project-users-info project-id)
                 (project-keywords project-id)
                 (project-notes project-id)
                 (project-settings project-id)
                 (project-files project-id))]
    {:project {:project-id project-id
               :name (:name fields)
               :project-uuid (:project-uuid fields)
               :members members
               :stats {:articles articles
                       :status-counts status-counts
                       :predict predict}
               :labels (project-labels project-id)
               :keywords keywords
               :notes notes
               :settings settings
               :files files}
     :users users}))
