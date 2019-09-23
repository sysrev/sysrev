(ns sysrev.project.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.string :as str]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.users :as su]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.notes :as snt]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction with-project-cache clear-project-cache]]
            [sysrev.project.compensation :as compensation]
            [sysrev.article.core :refer
             [set-article-flag remove-article-flag article-to-sql]]
            [sysrev.db.queries :as q]
            [sysrev.db.query-types :as qt]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer
             [in? map-values filter-values index-by req-un opt-keys]]
            [sysrev.shared.keywords :refer [canonical-keyword]])
  (:import java.util.UUID))

(s/def ::include-disabled? (s/nilable boolean?))

(def default-project-settings {:second-review-prob 0.5
                               :public-access true})

(def valid-permissions ["member" "admin" "owner" "resolve"])

(defn all-project-ids []
  (q/find :project {} :project-id, :order-by :project-id))

(defn all-projects
  "Returns seq of short info on all projects, for interactive use."
  []
  (q/find [:project :p] {}
          [:p.project-id :p.name [:%count.a.article-id :n-articles]]
          :left-join [:article:a :p.project-id]
          :group :p.project-id
          :order-by :p.project-id))

(defn-spec project-member (s/nilable ::sp/project-member)
  [project-id int?, user-id int?]
  (with-project-cache project-id [:users user-id :member]
    (q/find-one :project-member {:project-id project-id, :user-id user-id})))

(defn-spec add-project-member nil?
  "Add a user to the list of members of a project."
  [project-id int?, user-id int? &
   {:keys [permissions] :or {permissions ["member"]}}
   (opt-keys ::sp/permissions)]
  (db/with-clear-project-cache project-id
    (q/create :project-member {:project-id project-id, :user-id user-id
                               :permissions (db/to-sql-array "text" permissions)})
    ;; set their compensation to the project default
    (when-let [comp-id (compensation/get-default-project-compensation project-id)]
      (compensation/start-compensation-period-for-user! comp-id user-id))
    nil))

(defn-spec remove-project-member int?
  [project-id int?, user-id int?]
  (db/with-clear-project-cache project-id
    (q/delete :project-member {:project-id project-id :user-id user-id})))

(defn-spec set-member-permissions (s/nilable (s/coll-of map? :max-count 1))
  [project-id int?, user-id int?,
   permissions (s/and (s/coll-of string? :min-count 1)
                      #(every? (in? valid-permissions) %)) ]
  (db/with-clear-project-cache project-id
    (q/modify :project-member {:project-id project-id :user-id user-id}
              {:permissions (db/to-sql-array "text" permissions)}
              :returning [:user-id :permissions])))

(s/def ::parent-project-id ::sp/project-id)

(defn-spec create-project ::sp/project
  [project-name string? &
   {:keys [parent-project-id]} (opt-keys ::parent-project-id)]
  (q/create :project {:name project-name
                      :enabled true
                      :project-uuid (UUID/randomUUID)
                      :settings (db/to-jsonb default-project-settings)
                      :parent-project-id parent-project-id}
            :returning :*))

(defn-spec delete-project int?
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id int?]
  (db/with-clear-project-cache project-id
    (q/delete :project {:project-id project-id})))

(defn-spec enable-project! int?
  [project-id int?]
  (db/with-clear-project-cache project-id
    (q/modify :project {:project-id project-id} {:enabled true})))

(defn-spec disable-project! int?
  [project-id int?]
  (db/with-clear-project-cache project-id
    (q/modify :project {:project-id project-id} {:enabled false})))

(defn change-project-setting [project-id setting new-value]
  (with-transaction
    (let [cur-settings (-> (select :settings)
                           (from :project)
                           (where [:= :project-id project-id])
                           do-query first :settings)
          new-settings (assoc cur-settings setting new-value)]
      (assert (s/valid? ::sp/settings new-settings))
      (-> (sqlh/update :project)
          (sset {:settings (db/to-jsonb new-settings)})
          (where [:= :project-id project-id])
          do-execute)
      (clear-project-cache project-id)
      new-settings)))

(defn change-project-name [project-id project-name]
  (assert (string? project-name))
  (-> (sqlh/update :project)
      (sset {:name project-name})
      (where [:= :project-id project-id])
      do-execute)
  (clear-project-cache project-id)
  project-name)

(defn-spec project-article-count int?
  [project-id int?]
  (with-project-cache project-id [:articles :count]
    (q/find-count :article {:project-id project-id :enabled true})))

(defn project-article-pdf-count
  "Return number of article pdfs in project."
  [project-id]
  (with-project-cache project-id [:articles :pdf-count]
    (-> (q/select-project-articles project-id [:%count.*])
        (merge-join [:article-pdf :apdf] [:= :apdf.article-id :a.article-id])
        do-query first :count)))

(defn-spec delete-project-articles int?
  "Delete all articles from project."
  [project-id int?]
  (db/with-clear-project-cache project-id
    (q/delete :article {:project-id project-id})))

(defn-spec project-labels (s/map-of ::sc/label-id ::sl/label)
  [project-id int? &
   [include-disabled] (s/cat :include-disabled (s/? (s/nilable boolean?)))]
  (with-project-cache project-id [:labels :all include-disabled]
    (qt/find-label {:project-id project-id} :*
                   :include-disabled include-disabled
                   :index-by :label-id)))

(defn-spec project-consensus-label-ids (s/nilable (s/coll-of ::sc/label-id))
  [project-id int? &
   [include-disabled] (s/cat :include-disabled (s/? (s/nilable boolean?)))]
  (with-project-cache project-id [:labels :consensus include-disabled]
    (qt/find-label {:project-id project-id :consensus true} :label-id
                   :include-disabled include-disabled)))

(defn-spec project-overall-label-id (s/nilable ::sl/label-id)
  [project-id int?]
  (with-project-cache project-id [:labels :overall-label-id]
    (qt/find-label-1 {:project-id project-id :name "overall include"} :label-id)))

(defn-spec member-has-permission? boolean?
  [project-id int?, user-id int?, permission string?]
  (boolean (in? (:permissions (project-member project-id user-id)) permission)))

(defn-spec project-member-article-labels
  (s/and map?
         #(->> % :labels :confirmed (s/valid? ::sl/member-answers))
         #(->> % :labels :unconfirmed (s/valid? ::sl/member-answers))
         #(->> % :articles (s/valid? (s/map-of ::sc/article-id ::sa/article-partial))))
  "Returns a map of labels saved by `user-id` in `project-id`,
  and a map of entries for all articles referenced in the labels."
  [project-id int?, user-id int?]
  (with-project-cache project-id [:users user-id :labels :member-labels]
    (let [predict-run-id (q/project-latest-predict-run-id project-id)
          [labels articles]
          (pvalues (-> (q/select-project-article-labels
                        project-id nil [:al.article-id :al.label-id :al.answer :al.confirm-time])
                       (q/filter-label-user user-id)
                       (->> do-query (map #(assoc % :confirmed (not (nil? (:confirm-time %)))))))
                   (-> (q/select-project-articles
                        project-id [:a.article-id :a.primary-title :a.secondary-title :a.authors
                                    :a.year :a.remote-database-name])
                       ;; (q/with-article-predict-score predict-run-id)
                       (merge-where [:exists (q/select-user-article-labels
                                              user-id :a.article-id nil [:*])])
                       (->> do-query (index-by :article-id))))
          labels-map (fn [confirmed?]
                       (->> labels
                            (filter #(= (true? (:confirmed %)) confirmed?))
                            (group-by :article-id)
                            (map-values
                             #(->> % (map (fn [m] (dissoc m :article-id :confirmed)))))
                            (filter (fn [[aid cs]] (some (comp not nil? :answer) cs)))
                            (apply concat)
                            (apply hash-map)))
          [confirmed unconfirmed] (pvalues (labels-map true) (labels-map false))]
      {:labels {:confirmed confirmed, :unconfirmed unconfirmed}
       :articles articles})))

(defn-spec delete-member-labels-notes nil?
  "Deletes all labels and notes saved in `project-id` by `user-id`."
  [project-id int?, user-id int?]
  (db/with-clear-project-cache project-id
    (q/delete [:article-label :al] {:al.user-id user-id}
              :where (q/exists [:article :a] {:a.project-id project-id
                                              :a.article-id :al.article-id}))
    (q/delete [:article-label-history :alh] {:alh.user-id user-id}
              :where (q/exists [:article :a] {:a.project-id project-id
                                              :a.article-id :alh.article-id}))
    (q/delete [:article-note :an] {:an.user-id user-id}
              :where (q/exists [:project-note :pn] {:pn.project-id project-id
                                                    :pn.project-note-id :an.project-note-id}))
    nil))

(defn-spec ^:unused add-project-keyword map?
  "Creates an entry in `project-keyword` table, to be used by web client
  to highlight important words and link them to labels."
  [project-id int?, text string?, category string? &
   {:keys [user-id label-id label-value color] :as optionals}
   (opt-keys ::skw/user-id ::skw/label-id ::skw/label-value ::skw/color)]
  (db/with-clear-project-cache project-id
    (q/create :project-keyword (cond-> {:project-id project-id :value text :category category}
                                 user-id (assoc :user-id user-id)
                                 label-id (assoc :label-id label-id)
                                 ((comp not nil?) label-value)
                                 (assoc :label-value (db/to-jsonb label-value))
                                 color (assoc :color color))
              :returning :*)))

(defn-spec project-keywords ::skw/project-keywords-full
  "Returns map of `project-keyword` entries for a project."
  [project-id int?]
  (with-project-cache project-id [:keywords :all]
    (->> (do-query (q/select-project-keywords project-id [:*]))
         (map (fn [kw] (assoc kw :toks (->> (str/split (:value kw) #" ")
                                            (mapv canonical-keyword)))))
         (index-by :keyword-id))))

(defn-spec add-project-note ::snt/project-note
  "Defines an entry for a note type that can be saved by users on
  articles in the project. The default `name` of \"default\" is used
  for a generic free-text field shown alongside article labels during
  editing."
  [project-id int?,
   {:keys [name description max-length ordering] :as fields}
   (s/keys :opt-un [::snt/name ::snt/description ::snt/max-length ::snt/ordering])]
  (db/with-clear-project-cache project-id
    (q/create :project-note
              (merge {:project-id project-id
                      :max-length 1000 :name "default" :description "Notes"}
                     fields)
              :returning :*)))

(defn-spec project-notes ::snt/project-notes-map
  "Returns map of `project-note` entries for a project."
  [project-id int?]
  (with-project-cache project-id [:notes :all]
    (->> (q/find [:project :p] {:p.project-id project-id} :pn.*
                 :join [:project-note:pn :p.project-id])
         (index-by :name))))

(defn-spec project-settings ::sp/settings
  "Returns the current settings map for the project."
  [project-id int?]
  (q/find-one :project {:project-id project-id} :settings))

(defn-spec project-member-article-notes ::snt/member-notes-map
  "Returns a map of article notes saved by `user-id` in `project-id`."
  [project-id int?, user-id int?]
  (with-project-cache project-id [:users user-id :notes]
    (-> (q/select-project-articles project-id [:an.article-id :an.content :pn.name])
        (merge-join [:project :p] [:= :p.project-id :a.project-id])
        (q/with-article-note nil user-id)
        (->> do-query
             (group-by :article-id)
             (map-values (partial group-by :name))
             (map-values (partial map-values (comp :content first)))))))

(defn-spec project-user-ids (s/nilable (s/coll-of int?))
  "Returns sequence of user-id for all members of project."
  [project-id int?]
  (q/find :project-member {:project-id project-id} :user-id))

(defn project-id-from-register-hash [register-hash]
  (-> (q/select-project-where true [:project-id :project-uuid])
      (->> do-query
           (filter (fn [{:keys [project-id project-uuid]}]
                     (= register-hash (sutil/short-uuid project-uuid))))
           first :project-id)))

(defn-spec project-exists? boolean?
  "Does a project with project-id exist?"
  [project-id int? &
   {:keys [include-disabled?] :or {include-disabled? true}}
   (opt-keys ::include-disabled?)]
  (= project-id (q/find-one :project (cond-> {:project-id project-id}
                                       (not include-disabled?) (assoc :enabled true))
                            :project-id)))

(defn-spec project-has-labeled-articles? boolean?
  [project-id int?]
  (boolean (> (-> (select :%count.*)
                  (from :article-label)
                  (where [:in :article-id
                          (-> (select :article-id)
                              (from :article)
                              (where [:= :project-id project-id]))])
                  do-query first :count)
              0)))

(defn project-users-info [project-id]
  (with-project-cache project-id [:users-info]
    (->> (do-query (q/select-project-members project-id [:u.*]))
         (index-by :user-id)
         (map-values #(select-keys % [:user-id :user-uuid :email :verified :permissions])))))

(defn project-pmids [project-id]
  (->> (q/find [:article :a] {:a.project-id project-id
                              :ad.datasource-name "pubmed"
                              :a.enabled true}
               :ad.external-id
               :join [:article-data:ad :a.article-data-id]
               :where [:!= :ad.external-id nil])
       (map sutil/parse-number)
       (remove nil?) distinct vec))

(defn project-url-ids [project-id]
  (vec (q/find :project-url-id {:project-id project-id} [:url-id :user-id :date-created]
               :order-by [:date-created :desc])))

(defn project-id-from-url-id [url-id]
  (or (sutil/parse-integer url-id)
      (first (q/find :project-url-id {:url-id url-id} :project-id))))

(defn add-project-url-id
  "Adds a project-url-id entry (custom URL)"
  [project-id url-id & {:keys [user-id]}]
  (db/with-clear-project-cache project-id
    (q/delete :project-url-id {:project-id project-id :url-id url-id})
    (q/create :project-url-id {:project-id project-id :url-id url-id :user-id user-id})))

(defn all-public-projects []
  (-> (select :project-id :name :settings)
      (from :project)
      (where [:= :enabled true])
      (->> do-query
           (filter #(-> % :settings :public-access true?))
           (mapv #(select-keys % [:project-id :name])))))

(defn delete-all-projects-with-name [project-name]
  (q/delete :project {:name (not-empty project-name)}))

(defn get-single-user-project-ids [user-id]
  (let [project-ids (q/find :project-member {:user-id user-id} :project-id)
        p-members (when (seq project-ids)
                    (q/find :project-member {:project-id project-ids} [:user-id :project-id]
                            :group-by :project-id))]
    (keys (filter-values #(= 1 (count %)) p-members))))

;;;
;;; These are intended only for testing
;;;
(defn delete-compensation-by-id [project-id compensation-id]
  (q/delete :compensation-user-period      {:compensation-id compensation-id})
  (q/delete :compensation-project-default  {:compensation-id compensation-id})
  (q/delete :compensation-project          {:compensation-id compensation-id})
  (q/delete :compensation                  {:compensation-id compensation-id}))

(defn delete-project-compensations [project-id]
  (doseq [id (q/find :compensation-project {:project-id project-id} :compensation-id)]
    (delete-compensation-by-id project-id id)))

(defn member-count [project-id]
  (q/find-count :project-member {:project-id project-id}))

(defn delete-solo-projects-from-user [user-id]
  (doseq [project-id (get-single-user-project-ids user-id)]
    (delete-project-compensations project-id)
    (delete-project project-id)))

(defn project-article-ids
  "Returns list of all article ids in project. enabled may optionally be
  passed as true or false to filter by enabled status."
  [project-id & [enabled]]
  (assert (contains? #{nil true false} enabled))
  (q/find :article (cond-> {:project-id project-id}
                     (boolean? enabled) (merge {:enabled enabled}))
          :article-id))

(defn get-project-by-id [project-id]
  (q/find-one :project {:project-id project-id}))

(defn get-project-admins [project-id]
  (-> (select :pm.user-id :wb.email)
      (from [:project-member :pm])
      (join [:web-user :wb] [:= :wb.user_id :pm.user-id])
      (where [:and
              [:= :project-id project-id]
              [:= "admin" :%any.pm.permissions]])
      do-query))

(defn get-project-owner [project-id]
  (with-transaction
    (if-let [{:keys [group-id group-name] :as owner}
             (q/find-one [:project-group :pg] {:project-id project-id}
                         [:g.group-id :g.group-name]
                         :join [:groups:g :pg.group-id])]
      {:group-id group-id, :name group-name}
      (when-let [{:keys [user-id email] :as owner}
                 (q/find-one [:project-member :pm] {:pm.project-id project-id
                                                    "owner" :%any.pm.permissions}
                             [:u.user-id :u.email]
                             :join [:web-user:u :pm.user-id])]
        {:user-id user-id, :name (-> email (str/split #"@") first)}))))

(defn last-active
  "When was the last time an article-label was updated for project-id?"
  [project-id]
  (first (q/find [:article-label :al] {:a.project-id project-id} :al.updated-time
                 :join [:article:a :al.article-id]
                 :order-by [:al.updated-time :desc], :limit 1)))

(defn cleanup-browser-test-projects []
  (delete-all-projects-with-name "Sysrev Browser Test")
  (when-let [test-user-id (q/find-one :web-user {:email "browser+test@insilica.co"} :user-id)]
    (delete-solo-projects-from-user test-user-id)))

;; some notes:
;; https://www.postgresql.org/docs/current/textsearch.html
;; https://www.postgresql.org/docs/current/textsearch-controls.html
;; https://stackoverflow.com/questions/44285327/postgres-tsvector-with-relational-tables
;; https://dba.stackexchange.com/questions/15412/postgres-full-text-search-with-multiple-columns-why-concat-in-index-and-not-at?rq=1
;; https://dba.stackexchange.com/questions/107801/full-text-search-on-multiple-joined-tables?noredirect=1#comment196534_107801
;; https://dba.stackexchange.com/questions/108005/full-text-search-on-two-tsvector-columns
(defn search-projects
  [q  & {:keys [limit]
         :or {limit 10}}]
  "Return a list of projects using the search query q"
  (->> [(str "SELECT md.string as description, p.project_id, p.name, p.settings "
             "FROM project_description pd "
             "RIGHT JOIN project p on p.project_id = pd.project_id "
             "LEFT JOIN markdown md on md.markdown_id = pd.markdown_id "
             "WHERE p.enabled = true AND p.settings->>'public-access' = 'true' "
             "AND to_tsvector('english', coalesce(md.string,'') || ' ' || (coalesce(p.name,''))) @@ plainto_tsquery(?) "
             "LIMIT ? ")
        q limit]
       db/raw-query))
