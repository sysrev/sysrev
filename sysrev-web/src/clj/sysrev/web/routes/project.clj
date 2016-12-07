(ns sysrev.web.routes.project
  (:require
   [sysrev.web.app :refer [wrap-permissions current-user-id active-project]]
   [sysrev.db.core :refer [do-query do-execute]]
   [sysrev.db.queries :as q]
   [sysrev.db.users :as users]
   [sysrev.db.project :refer
    [project-labels project-member project-article-count]]
   [sysrev.db.articles :as articles]
   [sysrev.db.documents :as docs]
   [sysrev.db.labels :as labels]
   [sysrev.predict.report :refer [predict-summary]]
   [sysrev.util :refer
    [should-never-happen-exception map-values
     integerify-map-keys uuidify-map-keys]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [compojure.core :refer :all]
   [sysrev.db.project :as project]))

(declare project-info)

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
        {:result
         (labels/get-user-label-task
          (active-project request) (current-user-id request))}))

  ;; Sets and optionally confirms label values for an article
  (POST "/api/set-labels" request
        (wrap-permissions
         request [] ["member"]
         (let [user-id (current-user-id request)
               {:keys [article-id label-values confirm] :as body}
               (-> request :body integerify-map-keys uuidify-map-keys)]
           (assert (not (labels/user-article-confirmed? user-id article-id)))
           (labels/set-user-article-labels user-id article-id label-values false)
           (when confirm
             (labels/confirm-user-article-labels user-id article-id))
           {:result body})))

  (GET "/api/member-labels/:user-id" request
       (wrap-permissions
        request [] ["member"]
        (let [user-id (-> request :params :user-id Integer/parseInt)
              project-id (active-project request)]
          {:result
           (project/project-member-article-labels
            project-id user-id)})))
  
  ;; Returns map with full information on an article
  (GET "/api/article-info/:article-id" request
       (wrap-permissions
        request [] ["member"]
        (let [article-id (-> request :params :article-id Integer/parseInt)]
          (let [[article user-labels]
                (pvalues (q/query-article-by-id-full article-id)
                         (labels/article-user-labels-map article-id))]
            {:article (dissoc article :raw)
             :labels user-labels}))))

  (POST "/api/delete-member-labels" request
        (wrap-permissions
         request ["admin"] ["member"]
         (let [user-id (current-user-id request)
               project-id (active-project request)
               {:keys [verify-user-id]} (:body request)]
           (assert (= user-id verify-user-id) "verify-user-id mismatch")
           (project/delete-member-labels project-id user-id)
           {:result {:success true}}))))

(defn project-include-labels [project-id]
  (->>
   (-> (q/select-project-article-labels
        project-id true
        [:a.article-id :al.user-id :al.answer])
       (q/filter-overall-label)
       do-query)
   (group-by :article-id)))

(defn project-include-label-conflicts [project-id]
  (->> (project-include-labels project-id)
       (filter (fn [[aid labels]]
                 (< 1 (->> labels (map :answer) distinct count))))
       (apply concat)
       (apply hash-map)))

(defn project-user-inclusions [project-id]
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
   (apply hash-map)))

(defn project-members-info [project-id]
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
         (apply hash-map))))

(defn project-users-info [project-id]
  (->> (-> (q/select-project-members project-id [:u.*])
           do-query)
       (group-by :user-id)
       (map-values first)
       (map-values
        #(select-keys % [:user-id :user-uuid :email :verified :permissions]))))

(defn project-conflict-counts [project-id]
  (let [conflicts (project-include-label-conflicts project-id)
        resolved? (fn [[aid labels :as conflict]]
                    (> (count labels) 2))
        n-total (count conflicts)
        n-pending (->> conflicts (remove resolved?) count)
        n-resolved (- n-total n-pending)]
    {:total n-total
     :pending n-pending
     :resolved n-resolved}))

(defn project-label-counts [project-id]
  (let [labels (project-include-labels project-id)
        counts (->> labels vals (map count))]
    {:any (count labels)
     :single (->> counts (filter #(= % 1)) count)
     :double (->> counts (filter #(= % 2)) count)
     :multi (->> counts (filter #(> % 2)) count)}))

(defn project-label-value-counts
  "Returns counts of [true, false, unknown] label values for each label.
  true/false can be counted by the labels saved by all users.
  'unknown' values are counted when the user has set a value for at least one
  label on the article."
  [project-id]
  (let [entries
        (->>
         (-> (q/select-project-articles
              project-id [:al.article-id :l.label-id :al.user-id :al.answer])
             (q/join-article-labels)
             (q/join-article-label-defs)
             (q/filter-valid-article-label true)
             do-query)
         (group-by :user-id)
         (map-values
          (fn [uentries]
            (->> (group-by :article-id uentries)
                 (map-values
                  #(map-values (comp first (partial map :answer))
                               (group-by :label-id %)))))))
        user-articles (->> (keys entries)
                           (map
                            (fn [user-id]
                              (map (fn [article-id]
                                     [user-id article-id])
                                   (keys (get entries user-id)))))
                           (apply concat))
        label-ids (keys (project-labels project-id))
        ua-label-value
        (fn [user-id article-id label-id]
          (get-in entries [user-id article-id label-id]))
        answer-count
        (fn [label-id answer]
          (->> user-articles
               (filter
                (fn [[user-id article-id]]
                  (= answer (ua-label-value
                             user-id article-id label-id))))
               count))]
    (->> label-ids
         (map
          (fn [label-id]
            [label-id
             {:true (answer-count label-id true)
              :false (answer-count label-id false)
              :unknown (answer-count label-id nil)}]))
         (apply concat)
         (apply hash-map))))

(defn project-info [project-id]
  (let [[predict articles labels label-values conflicts members users]
        (pvalues (predict-summary (q/project-latest-predict-run-id project-id))
                 (project-article-count project-id)
                 (project-label-counts project-id)
                 (project-label-value-counts project-id)
                 (project-conflict-counts project-id)
                 (project-members-info project-id)
                 (project-users-info project-id))]
    {:project {:project-id project-id
               :members members
               :stats {:articles articles
                       :labels labels
                       :label-values label-values
                       :conflicts conflicts
                       :predict predict}
               :labels (project-labels project-id)}
     :users users}))
