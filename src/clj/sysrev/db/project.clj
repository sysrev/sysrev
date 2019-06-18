(ns sysrev.db.project
  (:require [clojure.spec.alpha :as s]
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
            [sysrev.db.compensation :as compensation]
            [sysrev.article.core :refer
             [set-article-flag remove-article-flag article-to-sql]]
            [sysrev.db.documents :as docs]
            [sysrev.db.queries :as q]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer
             [map-values in? short-uuid to-uuid parse-number ->map-with-key]]
            [sysrev.shared.keywords :refer [canonical-keyword]])
  (:import java.util.UUID))

(s/def ::include-disabled? (s/nilable boolean?))

(def default-project-settings {:second-review-prob 0.5
                               :public-access true})

(def valid-permissions ["member" "admin" "owner" "resolve"])

(defn all-project-ids []
  (-> (select :project-id)
      (from [:project :p])
      (order-by :project-id)
      (->> do-query (mapv :project-id))))

(defn all-projects
  "Returns seq of short info on all projects, for interactive use."
  []
  (-> (select :p.project-id :p.name :p.project-uuid
              [:%count.article-id :n-articles])
      (from [:project :p])
      (left-join [:article :a] [:= :a.project-id :p.project-id])
      (group :p.project-id)
      (order-by :p.date-created)
      do-query))

(defn project-member [project-id user-id]
  (with-project-cache project-id [:users user-id :member]
    (-> (select :*)
        (from :project-member)
        (where [:and [:= :project-id project-id] [:= :user-id user-id]])
        do-query first)))
;;;
(s/fdef project-member
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id)
  :ret (s/nilable ::sp/project-member))

(defn add-project-member
  "Add a user to the list of members of a project."
  [project-id user-id & {:keys [permissions] :or {permissions ["member"]}}]
  (try (-> (insert-into :project-member)
           (values [{:project-id project-id
                     :user-id user-id
                     :permissions (db/to-sql-array "text" permissions)}])
           (returning :membership-id)
           do-query first :membership-id)
       ;; set their compensation to the project default
       (when-let [default-compensation-id (compensation/get-default-project-compensation project-id)]
         (compensation/start-compensation-period-for-user! default-compensation-id user-id))
       (finally (clear-project-cache project-id))))
;;;
(s/fdef add-project-member
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id
               :opts (s/keys* :opt-un [::sp/permissions]))
  :ret (s/nilable ::sc/sql-id))

(defn remove-project-member
  "Remove a user from a project."
  [project-id user-id]
  (try (-> (delete-from :project-member)
           (where [:and [:= :project-id project-id] [:= :user-id user-id]])
           do-execute)
       (finally (clear-project-cache project-id))))
;;;
(s/fdef remove-project-member
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id)
  :ret (s/nilable integer?))

(defn set-member-permissions
  "Change the permissions for a project member."
  [project-id user-id permissions]
  (with-transaction
    (let [current-perms (:permissions (project-member project-id user-id))]
      (assert (not-empty permissions))
      (assert (every? (in? valid-permissions) permissions))
      (assert (if (in? current-perms "owner")
                (every? (in? permissions) ["owner" "admin"])
                true))
      (try (-> (sqlh/update :project-member)
               (sset {:permissions (db/to-sql-array "text" permissions)})
               (where [:and [:= :project-id project-id] [:= :user-id user-id]])
               (returning :user-id :permissions)
               do-query)
           (finally (clear-project-cache project-id))))))
;;;
(s/fdef set-member-permissions
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id
               :permissions ::sp/permissions)
  :ret (s/nilable (s/coll-of map? :max-count 1)))

(s/def ::parent-project-id (s/nilable ::sc/project-id))

(defn create-project
  "Create a new project entry."
  [project-name & {:keys [parent-project-id]}]
  (-> (insert-into :project)
      (values [{:name project-name
                :enabled true
                :project-uuid (UUID/randomUUID)
                :settings (db/to-jsonb default-project-settings)
                :parent-project-id parent-project-id}])
      (returning :*)
      do-query first))
;;;
(s/fdef create-project
  :args (s/cat :project-name ::sp/name
               :keys (s/keys* :opt-un [::parent-project-id]))
  :ret ::sp/project)

(defn delete-project
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id]
  (try (-> (delete-from :project)
           (where [:= :project-id project-id])
           do-execute first)
       (finally (clear-project-cache project-id))))
;;;
(s/fdef delete-project
  :args (s/cat :project-id (s/nilable ::sc/project-id))
  :ret (s/coll-of integer?))

(defn enable-project!
  "Set the enabled flag for project-id to false"
  [project-id]
  (try (-> (sqlh/update :project)
           (sset {:enabled true})
           (where [:= :project-id project-id])
           do-execute first)
       (finally (clear-project-cache project-id))))
;;;
(s/fdef enable-project!
  :args (s/cat :project-id int?)
  :ret int?)

(defn disable-project!
  "Set the enabled flag for project-id to false"
  [project-id]
  (try (-> (sqlh/update :project)
           (sset {:enabled false})
           (where [:= :project-id project-id])
           do-execute first)
       (finally (clear-project-cache project-id))))
;;;
(s/fdef disable-project!
  :args (s/cat :project-id int?)
  :ret int?)

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

(defn project-contains-public-id
  "Test if project contains an article with given `public-id` value."
  [public-id project-id]
  (if (nil? (sutil/parse-integer public-id))
    false
    (-> (q/select-article-where
         project-id [:= :a.public-id (str public-id)] [:%count.*])
        do-query first :count pos?)))
;;;
(s/fdef project-contains-public-id
  :args (s/cat :public-id ::sa/public-id
               :project-id ::sc/project-id)
  :ret boolean?)

(defn project-article-count
  "Return number of articles in project."
  [project-id]
  (with-project-cache project-id [:articles :count]
    (-> (q/select-project-articles project-id [:%count.*])
        do-query first :count)))
;;;
(s/fdef project-article-count
  :args (s/cat :project-id ::sc/project-id)
  :ret (s/and integer? nat-int?))

(defn project-article-pdf-count
  "Return number of article pdfs in project."
  [project-id]
  (with-project-cache project-id [:articles :pdf-count]
    (-> (q/select-project-articles project-id [:%count.*])
        (merge-join [:article-pdf :apdf] [:= :apdf.article-id :a.article-id])
        do-query first :count)))

(defn delete-project-articles
  "Delete all articles from project."
  [project-id]
  (try (with-transaction
         (-> (delete-from :article)
             (where [:= :project-id project-id])
             do-execute first))
       (finally (clear-project-cache project-id))))
;;;
(s/fdef delete-project-articles
  :args (s/cat :project-id ::sc/project-id)
  :ret (s/and integer? nat-int?))

(defn project-labels [project-id & [include-disabled?]]
  (with-project-cache project-id [:labels :all include-disabled?]
    (-> (q/select-label-where
         project-id true [:*] {:include-disabled? include-disabled?})
        (->> do-query (->map-with-key :label-id)))))
;;;
(s/fdef project-labels
  :args (s/cat :project-id ::sc/project-id
               :include-disabled? (s/? (s/nilable boolean?)))
  :ret (s/map-of ::sc/label-id ::sl/label))

(defn project-consensus-label-ids [project-id & [include-disabled?]]
  (with-project-cache project-id [:labels :consensus include-disabled?]
    (let [labels (project-labels project-id include-disabled?)
          label-ids (keys labels)]
      (->> label-ids (filter #(-> (get labels %) :consensus true?))))))

(defn project-overall-label-id [project-id]
  (with-project-cache project-id [:labels :overall-label-id]
    (:label-id (q/query-label-by-name project-id "overall include" [:label-id]))))
;;;
(s/fdef project-overall-label-id
  :args (s/cat :project-id ::sc/project-id)
  :ret ::sl/label-id)

(defn member-has-permission?
  "Does the user-id have the permission for project-id?"
  [project-id user-id permission]
  (boolean (in? (:permissions (project-member project-id user-id)) permission)))
;;;
(s/fdef member-has-permission?
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id
               :permission string?)
  :ret boolean?)

;; TODO: change result map to use spec-able keywords
(defn project-member-article-labels
  "Returns a map of labels saved by `user-id` in `project-id`,
  and a map of entries for all articles referenced in the labels."
  [project-id user-id]
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
                       (->> do-query (->map-with-key :article-id))))
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
;;;
(s/fdef project-member-article-labels
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id)
  :ret (s/and map?
              #(->> % :labels :confirmed
                    (s/valid? ::sl/member-answers))
              #(->> % :labels :unconfirmed
                    (s/valid? ::sl/member-answers))
              #(->> % :articles
                    (s/valid?
                     (s/map-of ::sc/article-id ::sa/article-partial)))))

(defn delete-member-labels-notes
  "Deletes all labels and notes saved in `project-id` by `user-id`."
  [project-id user-id]
  (assert (integer? project-id))
  (assert (integer? user-id))
  (with-transaction
    (-> (delete-from [:article-label :al])
        (q/filter-label-user user-id)
        (merge-where
         [:exists (q/select-article-where
                   project-id [:= :a.article-id :al.article-id] [:*])])
        do-execute)
    (-> (delete-from [:article-label-history :alh])
        (merge-where
         [:and
          [:= :alh.user-id user-id]
          [:exists (-> (select :*)
                       (from [:article :a])
                       (where [:and
                               [:= :a.project-id project-id]
                               [:= :a.article-id :alh.article-id]]))]])
        do-execute)
    (-> (delete-from [:article-note :an])
        (merge-where
         [:and
          [:= :an.user-id user-id]
          [:exists (-> (select :*)
                       (from [:project-note :pn])
                       (where [:and
                               [:= :pn.project-note-id :an.project-note-id]
                               [:= :pn.project-id project-id]]))]])
        do-execute))
  (clear-project-cache project-id)
  true)
;;;
(s/fdef delete-member-labels-notes
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id)
  :ret any?)

(defn add-project-keyword
  "Creates an entry in `project-keyword` table, to be used by web client
  to highlight important words and link them to labels."
  [project-id text category & [{:keys [user-id label-id label-value color]} :as optionals]]
  (let [label-id (and label-id (q/to-label-id label-id))]
    (try (-> (insert-into :project-keyword)
             (values [(cond-> {:project-id project-id
                               :value text
                               :category category}
                        user-id           (assoc :user-id user-id)
                        label-id          (assoc :label-id label-id)
                        ((comp not nil?)
                         label-value)     (assoc :label-value (db/to-jsonb label-value))
                        color             (assoc :color color))])
             (returning :*)
             do-query)
         (finally (clear-project-cache project-id)))))
;;;
(s/fdef add-project-keyword
  :args (s/cat :project-id ::sc/project-id
               :text ::skw/value
               :category ::skw/category
               :opts (s/keys :opt-un [::skw/user-id ::skw/label-id
                                      ::skw/label-value ::skw/color]))
  :ret any?)

(defn project-keywords
  "Returns a vector with all `project-keyword` entries for the project."
  [project-id]
  (with-project-cache project-id [:keywords :all]
    (->> (do-query (q/select-project-keywords project-id [:*]))
         (map (fn [kw] (assoc kw :toks (->> (str/split (:value kw) #" ")
                                            (mapv canonical-keyword)))))
         (->map-with-key :keyword-id))))
;;;
(s/fdef project-keywords
  :args (s/cat :project-id ::sc/project-id)
  :ret ::skw/project-keywords-full)

(defn add-project-note
  "Defines an entry for a note type that can be saved by users on articles
  in the project.
  The default `name` of \"default\" is used for a generic free-text field
  shown alongside article labels during editing."
  [project-id {:keys [name description max-length ordering] :as fields}]
  (try (-> (sqlh/insert-into :project-note)
           (values [(merge {:project-id project-id
                            :name "default"
                            :description "Notes"
                            :max-length 1000}
                           fields)])
           (returning :*)
           do-query first)
       (finally (clear-project-cache project-id))))
;;;
(s/fdef add-project-note
  :args (s/cat :project-id ::sc/project-id
               :fields (s/keys :opt-un
                               [::snt/name ::snt/description
                                ::snt/max-length ::snt/ordering]))
  :ret ::snt/project-note)

(defn project-notes
  "Returns a vector with all `project-note` entries for the project."
  [project-id]
  (with-project-cache project-id [:notes :all]
    (-> (q/select-project-where [:= :p.project-id project-id] [:pn.*])
        (q/with-project-note)
        (->> do-query (->map-with-key :name)))))
;;;
(s/fdef project-notes
  :args (s/cat :project-id ::sc/project-id)
  :ret ::snt/project-notes-map)

(defn project-settings
  "Returns the current settings map for the project."
  [project-id]
  (-> (q/select-project-where [:= :p.project-id project-id] [:settings])
      do-query first :settings))
;;;
(s/fdef project-settings
  :args (s/cat :project-id ::sc/project-id)
  :ret ::sp/settings)

(defn project-member-article-notes
  "Returns a map of article notes saved by `user-id` in `project-id`."
  [project-id user-id]
  (with-project-cache project-id [:users user-id :notes]
    (-> (q/select-project-articles project-id [:an.article-id :an.content :pn.name])
        (merge-join [:project :p] [:= :p.project-id :a.project-id])
        (q/with-article-note nil user-id)
        (->> do-query
             (group-by :article-id)
             (map-values (partial group-by :name))
             (map-values (partial map-values (comp :content first)))))))
;;;
(s/fdef project-member-article-notes
  :args (s/cat :project-id ::sc/project-id
               :user-id ::sc/user-id)
  :ret ::snt/member-notes-map)

(defn project-user-ids
  "Returns a vector of `user-id` for the members of `project-id`.
   If `admin?` is a boolean, filter by user admin status."
  [project-id & [admin?]]
  (-> (q/select-project-members project-id [:u.user-id])
      (q/filter-admin-user admin?)
      (->> do-query (mapv :user-id))))
;;;
(s/fdef project-user-ids
  :args (s/cat :project-id ::sc/project-id
               :admin? (s/? (s/nilable boolean?)))
  :ret (s/coll-of map?))

(defn project-id-from-register-hash [register-hash]
  (-> (q/select-project-where true [:project-id :project-uuid])
      (->> do-query
           (filter (fn [{:keys [project-id project-uuid]}]
                     (= register-hash (short-uuid project-uuid))))
           first :project-id)))

(defn project-exists?
  "Does a project with project-id exist?"
  [project-id & {:keys [include-disabled?] :or {include-disabled? true}}]
  (= project-id (-> (select :project-id)
                    (from [:project :p])
                    (where [:and
                            [:= :p.project-id project-id]
                            (if include-disabled? true
                                [:= :p.enabled true])])
                    do-query first :project-id)))
;;;
(s/fdef project-exists?
  :args (s/cat :project-id int?
               :keys (s/keys* :opt-un [::include-disabled?]))
  :ret boolean?)

(defn project-has-labeled-articles?
  [project-id]
  (boolean (> (-> (select :%count.*)
                  (from :article-label)
                  (where [:in :article-id
                          (-> (select :article-id)
                              (from :article)
                              (where [:= :project-id project-id]))])
                  do-query first :count)
              0)))
;;;
(s/fdef project-has-labeled-articles?
  :args (s/cat :project-id int?)
  :ret boolean?)

(defn project-users-info [project-id]
  (with-project-cache project-id [:users-info]
    (->> (do-query (q/select-project-members project-id [:u.*]))
         (->map-with-key :user-id)
         (map-values #(select-keys % [:user-id :user-uuid :email :verified :permissions])))))

(defn project-pmids
  "Given a project-id, return all PMIDs associated with the project"
  [project-id]
  (-> (select :public-id) (from :article)
      (where [:and [:= :project-id project-id] [:= :enabled true]])
      (->> do-query
           (mapv :public-id)
           (mapv parse-number)
           (filterv (comp not nil?)))))

(defn project-url-ids [project-id]
  (-> (select :url-id :user-id :date-created)
      (from [:project-url-id :purl])
      (where [:= :project-id project-id])
      (order-by [:date-created :desc])
      (->> do-query vec)))

;; TODO: support filtering by project owner
(defn project-id-from-url-id [url-id]
  (or (sutil/parse-integer url-id)
      (-> (select :project-id)
          (from [:project-url-id :purl])
          (where [:= :url-id url-id])
          do-query first :project-id)))

(defn add-project-url-id
  "Adds a project-url-id entry (custom URL)"
  [project-id url-id & {:keys [user-id]}]
  (try (with-transaction
         (-> (delete-from :project-url-id)
             (where [:and [:= :project-id project-id] [:= :url-id url-id]])
             do-execute)
         (-> (insert-into :project-url-id)
             (values [{:project-id project-id, :url-id url-id, :user-id user-id}])
             do-execute))
       (finally (clear-project-cache project-id))))

(defn all-public-projects []
  (-> (select :project-id :name :settings)
      (from :project)
      (where [:= :enabled true])
      (->> do-query
           (filter #(-> % :settings :public-access true?))
           (mapv #(select-keys % [:project-id :name])))))

(defn delete-all-projects-with-name [project-name]
  (assert (string? project-name))
  (assert (not-empty project-name))
  (q/delete-by-id :project :name project-name))

(defn get-single-user-project-ids [user-id]
  (let [project-ids (-> (select :project-id)
                        (from :project-member)
                        (where [:= :user-id user-id])
                        (->> do-query (map :project-id) vec))
        member-counts (when (not-empty project-ids)
                        (-> (select :user-id :project-id)
                            (from :project-member)
                            (where [:in :project-id project-ids])
                            (->> do-query (group-by :project-id) (map-values count))))]
    (->> (vec member-counts)
         (map (fn [[project-id n-members]]
                (when (= 1 n-members) project-id)))
         (remove nil?))))

(defn delete-solo-projects-from-user [user-id]
  (doseq [project-id (get-single-user-project-ids user-id)]
    (delete-project project-id)))

(defn project-article-ids
  "Returns list of all article ids in project. enabled may optionally be
  passed as true or false to filter by enabled status."
  [project-id & [enabled]]
  (assert (contains? #{nil true false} enabled))
  (-> (select :article-id) (from :article)
      (where [:and
              [:= :project-id project-id]
              (if (nil? enabled) true
                  [:= :enabled enabled])])
      (->> do-query (mapv :article-id))))

(defn get-project-by-id
  "Return a project by its id"
  [project-id]
  (-> (select :*)
      (from :project)
      (where [:= :project-id project-id])
      do-query
      first))

(defn get-project-owner [project-id]
  (with-transaction
    (assert (integer? (-> (select :project-id)
                          (from :project)
                          (where [:= :project-id project-id])
                          do-query first :project-id)))
    (if-let [project-group (-> (select :group-id)
                               (from :project-group)
                               (where [:= :project-id project-id])
                               do-query first :group-id)]
      {:group-id project-group}
      (-> (select :user-id)
          (from :project-member)
          (where [:and
                  [:= :project-id project-id]
                  [:= "owner" :%any.permissions]])
          do-query first))))

(defn search-project-name
  [q]
  (->>
   [(str "SELECT md.string as description, p.project_id, p.name "
          "FROM project_description pd "
          "RIGHT JOIN project p on p.project_id = pd.project_id "
          "LEFT JOIN markdown md on md.markdown_id = pd.markdown_id "
          "WHERE (p.name ilike ?) "
          "AND p.enabled = true AND p.settings->>'public-access' = 'true' "
          "ORDER BY p.date_created ")
    (str "%" q "%")]
   db/raw-query))

(defn search-project-description
  [q]
    (->>
   [(str "SELECT md.string as description, p.project_id, p.name "
          "FROM project_description pd "
          "RIGHT JOIN project p on p.project_id = pd.project_id "
          "LEFT JOIN markdown md on md.markdown_id = pd.markdown_id "
          "WHERE (md.string ilike ?) "
          "AND p.enabled = true AND p.settings->>'public-access' = 'true' "
          "ORDER BY p.date_created ")
    (str "%" q "%")]
   db/raw-query))

;; https://news.ycombinator.com/item?id=12621950
;; http://rachbelaid.com/postgres-full-text-search-is-good-enough/

#_(defn search-projects
  [q]
  (merge (search-project-name q)
         (search-project-description q)))
(defn search-projects
  [q]
  "Return a list of projects using the search query q"
  (with-transaction
    (let [project-ids (->> [#_
                            (str "SELECT project_id,date_created "
                                 "FROM project "
                                 "WHERE (name ilike ?) AND enabled = true AND settings->>'public-access' = 'true' "
                                 "ORDER BY date_created LIMIT ?")
                            (str "SELECT md.string as description, p.project_id, p.name, p.settings "
                                 "FROM project_description pd "
                                 "RIGHT JOIN project p on p.project_id = pd.project_id "
                                 "LEFT JOIN markdown md on md.markdown_id = pd.markdown_id "
                                 "WHERE (md.string ilike ?)"
                                 ;;(p.name ilike ?)
                                 "OR (p.name ilike ?) "
                                 "AND p.enabled = true AND p.settings->>'public-access' = 'true' "
                                 "ORDER BY p.date_created "
                                 "LIMIT ?"
                                 )
                            (str "%" q "%")
                            (str "%" q "%")
                            10
                            ]
                           db/raw-query
                           ;;(map :project-id)
                           )]
      (if (empty? project-ids)
        []
        ;;project-ids
        project-ids
        #_(-> (select [:md.string :description] :p.project-id :p.name)
            (from [:project-description :pd])
            (right-join [:project :p] [:= :p.project-id :pd.project-id])
            (left-join [:markdown :md] [:= :md.markdown-id :pd.markdown-id])
            (where [:in :p.project-id
                    project-ids])
            do-query)))))
