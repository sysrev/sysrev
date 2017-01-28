(ns sysrev.web.routes.project
  (:require
   [sysrev.web.app :refer [wrap-permissions current-user-id active-project]]
   [sysrev.db.core :refer
    [do-query do-execute with-project-cache]]
   [sysrev.db.queries :as q]
   [sysrev.db.users :as users]
   [sysrev.db.project :refer
    [project-labels project-member project-article-count project-keywords
     project-notes]]
   [sysrev.db.articles :as articles]
   [sysrev.db.documents :as docs]
   [sysrev.db.labels :as labels]
   [sysrev.predict.report :refer [predict-summary]]
   [sysrev.shared.util :refer [map-values]]
   [sysrev.shared.keywords :refer [process-keywords format-abstract]]
   [sysrev.util :refer
    [should-never-happen-exception in?
     integerify-map-keys uuidify-map-keys]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [compojure.core :refer :all]
   [sysrev.db.project :as project]))

(declare project-info)
(declare prepare-article-response)

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
         (prepare-article-response
          (labels/get-user-label-task
           (active-project request) (current-user-id request)))}))

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

  (POST "/api/set-article-note" request
        (wrap-permissions
         request [] ["member"]
         (let [user-id (current-user-id request)
               {:keys [article-id name content] :as body}
               (-> request :body integerify-map-keys uuidify-map-keys)]
           (articles/set-user-article-note article-id user-id name content)
           {:result body})))

  (GET "/api/member-labels/:user-id" request
       (wrap-permissions
        request [] ["member"]
        (let [user-id (-> request :params :user-id Integer/parseInt)
              project-id (active-project request)]
          {:result
           (merge
            (project/project-member-article-labels project-id user-id)
            {:notes
             (project/project-member-article-notes project-id user-id)})})))
  
  ;; Returns map with full information on an article
  (GET "/api/article-info/:article-id" request
       (wrap-permissions
        request [] ["member"]
        (let [project-id (active-project request)
              article-id (-> request :params :article-id Integer/parseInt)]
          (let [[article user-labels user-notes]
                (pvalues
                 (q/query-article-by-id-full article-id)
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
           {:result {:success true}}))))

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
          [:a.article-id :al.user-id :al.answer])
         (q/filter-overall-label)
         do-query)
     (group-by :article-id))))

(defn project-include-label-conflicts [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-label-conflicts]
    (->> (project-include-labels project-id)
         (filter (fn [[aid labels]]
                   (< 1 (->> labels (map :answer) distinct count))))
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

(defn project-conflict-counts [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :conflict-counts]
    (let [conflicts (project-include-label-conflicts project-id)
          resolved? (fn [[aid labels :as conflict]]
                      (> (count labels) 2))
          n-total (count conflicts)
          n-pending (->> conflicts (remove resolved?) count)
          n-resolved (- n-total n-pending)]
      {:total n-total
       :pending n-pending
       :resolved n-resolved})))

(defn project-label-counts [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :include-label-counts]
    (let [labels (project-include-labels project-id)
          counts (->> labels vals (map count))]
      {:any (count labels)
       :single (->> counts (filter #(= % 1)) count)
       :double (->> counts (filter #(= % 2)) count)
       :multi (->> counts (filter #(> % 2)) count)})))

(defn project-label-value-counts
  "Returns counts of all possible values for each label. `nil` values are
  counted when the user has set a value for at least one label on the article."
  [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :label-value-counts]
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
          ual-answer
          (fn [user-id article-id label-id]
            (get-in entries [user-id article-id label-id]))
          value-count
          (fn [label-id value]
            (->> user-articles
                 (filter
                  (fn [[user-id article-id]]
                    (let [answer (ual-answer
                                  user-id article-id label-id)]
                      (if (sequential? answer)
                        (in? answer value)
                        (= answer value)))))
                 count))]
      (->> label-ids
           (map
            (fn [label-id]
              (let [lvalues
                    (conj (labels/label-possible-values label-id) nil)]
                {label-id
                 (->>
                  lvalues
                  (map
                   (fn [value]
                     {(pr-str value)
                      (value-count label-id value)}))
                  (apply merge))})))
           (apply merge)))))

(defn project-inclusion-value-counts
  "Returns counts of [true, false, unknown] inclusion values for each label.
  true/false can be counted by the labels saved by all users.
  'unknown' values are counted when the user has set a value for at least one
  label on the article."
  [project-id]
  (with-project-cache
    project-id [:label-values :confirmed :inclusion-value-counts]
    (let [entries
          (->>
           (-> (q/select-project-articles
                project-id [:al.article-id :l.label-id :al.user-id :al.inclusion])
               (q/join-article-labels)
               (q/join-article-label-defs)
               (q/filter-valid-article-label true)
               do-query)
           (group-by :user-id)
           (map-values
            (fn [uentries]
              (->> (group-by :article-id uentries)
                   (map-values
                    #(map-values (comp first (partial map :inclusion))
                                 (group-by :label-id %)))))))
          user-articles (->> (keys entries)
                             (map
                              (fn [user-id]
                                (map (fn [article-id]
                                       [user-id article-id])
                                     (keys (get entries user-id)))))
                             (apply concat))
          label-ids (keys (project-labels project-id))
          ual-inclusion
          (fn [user-id article-id label-id]
            (get-in entries [user-id article-id label-id]))
          inclusion-count
          (fn [label-id inclusion]
            (->> user-articles
                 (filter
                  (fn [[user-id article-id]]
                    (= inclusion (ual-inclusion
                                  user-id article-id label-id))))
                 count))]
      (->> label-ids
           (map
            (fn [label-id]
              [label-id
               {"true" (inclusion-count label-id true)
                "false" (inclusion-count label-id false)
                "nil" (inclusion-count label-id nil)}]))
           (apply concat)
           (apply hash-map)))))

(defn project-info [project-id]
  (let [[predict articles labels inclusion-values label-values
         conflicts members users keywords notes]
        (pvalues (predict-summary (q/project-latest-predict-run-id project-id))
                 (project-article-count project-id)
                 (project-label-counts project-id)
                 (project-inclusion-value-counts project-id)
                 (project-label-value-counts project-id)
                 (project-conflict-counts project-id)
                 (project-members-info project-id)
                 (project-users-info project-id)
                 (project-keywords project-id)
                 (project-notes project-id))]
    {:project {:project-id project-id
               :members members
               :stats {:articles articles
                       :labels labels
                       :inclusion-values inclusion-values
                       :label-values label-values
                       :conflicts conflicts
                       :predict predict}
               :labels (project-labels project-id)
               :keywords keywords
               :notes notes}
     :users users}))
