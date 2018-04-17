(ns sysrev.db.project
  (:require
   [clojure.spec.alpha :as s]
   [sysrev.shared.spec.core :as sc]
   [sysrev.shared.spec.article :as sa]
   [sysrev.shared.spec.project :as sp]
   [sysrev.shared.spec.labels :as sl]
   [sysrev.shared.spec.users :as su]
   [sysrev.shared.spec.keywords :as skw]
   [sysrev.shared.spec.notes :as snt]
   [sysrev.db.core :refer
    [do-query do-execute to-sql-array sql-cast with-project-cache
     clear-project-cache clear-query-cache cached-project-ids to-jsonb
     with-transaction]]
   [sysrev.db.articles :refer
    [set-article-flag remove-article-flag article-to-sql]]
   [sysrev.db.documents :as docs]
   [sysrev.db.queries :as q]
   [sysrev.files.stores :as files]
   [sysrev.util]
   [sysrev.shared.util :refer
    [map-values in? short-uuid to-uuid parse-number]]
   [sysrev.shared.keywords :refer [canonical-keyword]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [clojure.string :as str])
  (:import java.util.UUID))

(def default-project-settings
  {:second-review-prob 0.5})

(defn all-project-ids []
  (-> (select :project-id)
      (from [:project :p])
      (order-by :project-id)
      (->> do-query (mapv :project-id))))

(defn all-projects
  "Returns seq of short info on all projects, for interactive use."
  []
  (-> (select :p.project-id
              :p.name
              :p.project-uuid
              [:%count.article-id :n-articles])
      (from [:project :p])
      (left-join [:article :a] [:= :a.project-id :p.project-id])
      (group :p.project-id)
      (order-by :p.date-created)
      do-query))
;;
(s/fdef all-projects
        :args (s/cat)
        :ret (s/coll-of map?))

(defn add-project-member
  "Add a user to the list of members of a project."
  [project-id user-id &
   {:keys [permissions]
    :or {permissions ["member"]}}]
  (let [project-id (q/to-project-id project-id)
        user-id (q/to-user-id user-id)
        entry {:project-id project-id
               :user-id user-id
               :permissions (to-sql-array "text" permissions)}]
    (try
      (-> (insert-into :project-member)
          (values [entry])
          (returning :membership-id)
          do-query first :membership-id)
      (finally
        (clear-project-cache project-id)))))
;;
(s/fdef add-project-member
        :args (s/cat
               :project-id ::sc/project-id
               :user-id ::sc/user-id
               :opts (s/keys* :opt-un [::sp/permissions]))
        :ret (s/nilable ::sc/sql-id))

(defn remove-project-member
  "Remove a user from a project."
  [project-id user-id]
  (let [project-id (q/to-project-id project-id)
        user-id (q/to-user-id user-id)]
    (try
      (-> (delete-from :project-member)
          (where [:and
                  [:= :project-id project-id]
                  [:= :user-id user-id]])
          do-execute)
      (finally
        (clear-project-cache project-id)))))
;;
(s/fdef remove-project-member
        :args (s/cat :project-id ::sc/project-id
                     :user-id ::sc/user-id)
        :ret (s/nilable integer?))

(defn set-member-permissions
  "Change the permissions for a project member."
  [project-id user-id permissions]
  (let [project-id (q/to-project-id project-id)
        user-id (q/to-user-id user-id)]
    (try
      (-> (sqlh/update :project-member)
          (sset {:permissions (to-sql-array "text" permissions)})
          (where [:and
                  [:= :project-id project-id]
                  [:= :user-id user-id]])
          (returning :user-id :permissions)
          do-query)
      (finally
        (clear-project-cache project-id)))))
;;
(s/fdef set-member-permissions
        :args (s/cat :project-id ::sc/project-id
                     :user-id ::sc/user-id
                     :permissions ::sp/permissions)
        :ret (s/nilable (s/coll-of map? :max-count 1)))

(defn create-project
  "Create a new project entry."
  [project-name & {:keys [parent-project-id]}]
  (try
    (-> (insert-into :project)
        (values [{:name project-name
                  :enabled true
                  :project-uuid (UUID/randomUUID)
                  :settings (to-jsonb default-project-settings)
                  :parent-project-id parent-project-id}])
        (returning :*)
        do-query
        first)
    (finally
      (clear-query-cache))))
;;
(s/fdef create-project
        :args (s/cat :project-name ::sp/name)
        :ret ::sp/project)

(defn delete-project
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id]
  (let [project-id (q/to-project-id project-id)]
    (try
      (-> (delete-from :project)
          (where [:= :project-id project-id])
          do-execute first)
      (finally
        (clear-query-cache)))))
;;
(s/fdef delete-project
        :args (s/cat :project-id ::sc/project-id)
        :ret integer?)

(defn disable-project!
  "Set the enabled flag for project-id to false"
  [project-id]
  (try
    (-> (sqlh/update :project)
        (sset {:enabled false})
        (where [:= :project-id project-id])
        do-execute
        first)
    (finally
      (clear-query-cache))))

(s/fdef disable-project!
        :args (s/cat :project-id int?)
        :ret int?)

(defn change-project-setting [project-id setting new-value]
  (let [project-id (q/to-project-id project-id)
        cur-settings (-> (select :settings)
                         (from :project)
                         (where [:= :project-id project-id])
                         do-query first :settings)
        new-settings (assoc cur-settings setting new-value)]
    (assert (s/valid? ::sp/settings new-settings))
    (-> (sqlh/update :project)
        (sset {:settings (to-jsonb new-settings)})
        (where [:= :project-id project-id])
        do-execute)
    (clear-project-cache project-id)
    new-settings))

(defn project-contains-public-id
  "Test if project contains an article with given `public-id` value."
  [public-id project-id]
  (let [project-id (q/to-project-id project-id)]
    (-> (q/select-article-where
         project-id [:= :a.public-id (str public-id)] [:%count.*])
        do-query first :count pos?)))
;;
(s/fdef project-contains-public-id
        :args (s/cat :public-id ::sa/public-id
                     :project-id ::sc/project-id)
        :ret boolean?)

(defn project-article-count
  "Return number of articles in project."
  [project-id]
  (let [project-id (q/to-project-id project-id)]
    (with-project-cache
      project-id [:articles :count]
      (-> (q/select-project-articles project-id [:%count.*])
          do-query first :count))))
;;
(s/fdef project-article-count
        :args (s/cat :project-id ::sc/project-id)
        :ret (s/and integer? nat-int?))

(defn delete-project-articles
  "Delete all articles from project."
  [project-id]
  (let [project-id (q/to-project-id project-id)]
    (try
      (with-transaction
        (-> (delete-from :article)
            (where [:= :project-id project-id])
            do-execute first))
      (finally
        (clear-project-cache project-id)))))
;;
(s/fdef delete-project-articles
        :args (s/cat :project-id ::sc/project-id)
        :ret (s/and integer? nat-int?))

(defn project-labels [project-id]
  (let [project-id (q/to-project-id project-id)]
    (with-project-cache
      project-id [:labels :all]
      (->>
       (-> (q/select-label-where project-id true [:*])
           do-query)
       (group-by :label-id)
       (map-values first)))))
;;
(s/fdef project-labels
        :args (s/cat :project-id ::sc/project-id)
        :ret (s/map-of ::sc/label-id ::sl/label))

(defn project-overall-label-id [project-id]
  (let [project-id (q/to-project-id project-id)]
    (with-project-cache
      project-id [:labels :overall-label-id]
      (:label-id
       (q/query-label-by-name project-id "overall include" [:label-id])))))
;;
(s/fdef project-overall-label-id
        :args (s/cat :project-id ::sc/project-id)
        :ret ::sl/label-id)

(defn project-member [project-id user-id]
  (let [project-id (q/to-project-id project-id)
        user-id (q/to-user-id user-id)]
    (with-project-cache
      project-id [:users user-id :member]
      (-> (select :*)
          (from :project-member)
          (where [:and
                  [:= :project-id project-id]
                  [:= :user-id user-id]])
          do-query first))))
;;
(s/fdef project-member
        :args (s/cat :project-id ::sc/project-id
                     :user-id ::sc/user-id)
        :ret (s/nilable ::sp/project-member))
;;

(defn member-has-permission?
  "Does the user-id have the permission for project-id?"
  [project-id user-id permission]
  (boolean (in? (:permissions (project-member project-id user-id)) permission)))

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
  (let [project-id (q/to-project-id project-id)
        user-id (q/to-user-id user-id)]
    (with-project-cache
      project-id [:users user-id :labels :member-labels]
      (let [predict-run-id (q/project-latest-predict-run-id project-id)
            [labels articles]
            (pvalues
             (->>
              (-> (q/select-project-article-labels
                   project-id nil
                   [:al.article-id :al.label-id :al.answer :al.confirm-time])
                  (q/filter-label-user user-id)
                  do-query)
              (map
               #(-> %
                    (assoc :confirmed (not (nil? (:confirm-time %))))
                    #_ (dissoc :confirm-time))))
             (->>
              (-> (q/select-project-articles
                   project-id
                   [:a.article-id :a.primary-title :a.secondary-title :a.authors
                    :a.year :a.remote-database-name])
                  ;; (q/with-article-predict-score predict-run-id)
                  (merge-where
                   [:exists
                    (q/select-user-article-labels
                     user-id :a.article-id nil [:*])])
                  do-query)
              (group-by :article-id)
              (map-values first)))
            labels-map (fn [confirmed?]
                         (->> labels
                              (filter #(= (true? (:confirmed %)) confirmed?))
                              (group-by :article-id)
                              (map-values
                               #(map (fn [m]
                                       (dissoc m :article-id :confirmed))
                                     %))
                              (filter
                               (fn [[aid cs]]
                                 (some (comp not nil? :answer) cs)))
                              (apply concat)
                              (apply hash-map)))
            [confirmed unconfirmed]
            (pvalues (labels-map true) (labels-map false))]
        {:labels {:confirmed confirmed
                  :unconfirmed unconfirmed}
         :articles articles}))))
;;
(s/fdef
 project-member-article-labels
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

;; TODO - finish/use this?
(defn project-email-domains
  [project-id]
  (let [emails (->>
                (-> (select :u.email)
                    (from [:web-user :u])
                    (join [:project-member :m]
                          [:= :m.user-id :u.user-id])
                    (where [:= :m.project-id project-id])
                    do-query)
                (mapv :email))]
    emails))

(defn delete-member-labels-notes
  "Deletes all labels and notes saved in `project-id` by `user-id`."
  [project-id user-id]
  (let [project-id (q/to-project-id project-id)
        user-id (q/to-user-id user-id)]
    (assert (integer? project-id))
    (assert (integer? user-id))
    (with-transaction
      (-> (delete-from [:article-label :al])
          (q/filter-label-user user-id)
          (merge-where
           [:exists
            (q/select-article-where
             project-id [:= :a.article-id :al.article-id] [:*])])
          do-execute)
      (-> (delete-from [:article-label-history :alh])
          (merge-where
           [:and
            [:= :alh.user-id user-id]
            [:exists
             (-> (select :*)
                 (from [:article :a])
                 (where [:and
                         [:= :a.project-id project-id]
                         [:= :a.article-id :alh.article-id]]))]])
          do-execute)
      (-> (delete-from [:article-note :an])
          (merge-where
           [:and
            [:= :an.user-id user-id]
            [:exists
             (-> (select :*)
                 (from [:project-note :pn])
                 (where [:and
                         [:= :pn.project-note-id :an.project-note-id]
                         [:= :pn.project-id project-id]]))]])
          do-execute))
    (clear-project-cache project-id)
    true))
;;
(s/fdef delete-member-labels-notes
        :args (s/cat :project-id ::sc/project-id
                     :user-id ::sc/user-id)
        :ret any?)

(defn add-project-keyword
  "Creates an entry in `project-keyword` table, to be used by web client
  to highlight important words and link them to labels."
  [project-id text category
   & [{:keys [user-id label-id label-value color]}
      :as optionals]]
  (let [project-id (q/to-project-id project-id)
        user-id (and user-id (q/to-user-id user-id))
        label-id (and label-id (q/to-label-id label-id))]
    (try
      (-> (insert-into :project-keyword)
          (values [(cond->
                       {:project-id project-id
                        :value text
                        :category category}
                     user-id (assoc :user-id user-id)
                     label-id (assoc :label-id label-id)
                     ((comp not nil?) label-value)
                     (assoc :label-value (to-jsonb label-value))
                     color (assoc :color color))])
          (returning :*)
          do-query)
      (finally
        (clear-project-cache project-id)))))
;;
(s/fdef
 add-project-keyword
 :args (s/cat :project-id ::sc/project-id
              :text ::skw/value
              :category ::skw/category
              :opts
              (s/keys :opt-un
                      [::skw/user-id ::skw/label-id
                       ::skw/label-value ::skw/color]))
 :ret any?)

(defn project-keywords
  "Returns a vector with all `project-keyword` entries for the project."
  [project-id]
  (let [project-id (q/to-project-id project-id)]
    (with-project-cache
      project-id [:keywords :all]
      (->> (q/select-project-keywords project-id [:*])
           do-query
           (map
            (fn [kw]
              (assoc kw :toks
                     (->> (str/split (:value kw) #" ")
                          (mapv canonical-keyword)))))
           (group-by :keyword-id)
           (map-values first)))))
;;
(s/fdef project-keywords
        :args (s/cat :project-id ::sc/project-id)
        :ret ::skw/project-keywords-full)

(defn disable-missing-abstracts [project-id min-length]
  (let [project-id (q/to-project-id project-id)]
    (with-transaction
      (-> (select :article-id)
          (from [:article :a])
          (where [:and
                  [:= :a.project-id project-id]
                  [:or
                   [:= :a.abstract nil]
                   [:= (sql/call :char_length :a.abstract) 0]]])
          (->> do-query
               (map :article-id)
               (mapv #(set-article-flag % "no abstract" true))))
      (-> (select :article-id)
          (from [:article :a])
          (where
           [:and
            [:= :a.project-id project-id]
            [:!= :a.abstract nil]
            [:< (sql/call :char_length :a.abstract) min-length]])
          (->> do-query
               (map :article-id)
               (mapv #(set-article-flag % "short abstract" true
                                        {:min-length min-length}))))
      (clear-project-cache project-id)
      true)))
;;
(s/fdef disable-missing-abstracts
        :args (s/cat :project-id ::sc/project-id
                     :min-length (s/and integer? nat-int?))
        :ret any?)

(defn add-project-note
  "Defines an entry for a note type that can be saved by users on articles
  in the project.
  The default `name` of \"default\" is used for a generic free-text field
  shown alongside article labels during editing."
  [project-id {:keys [name description max-length ordering]
               :as fields}]
  (let [project-id (q/to-project-id project-id)]
    (clear-project-cache project-id)
    (-> (sqlh/insert-into :project-note)
        (values [(merge {:project-id project-id
                         :name "default"
                         :description "Notes"
                         :max-length 1000}
                        fields)])
        (returning :*)
        do-query first)))
;;
(s/fdef
 add-project-note
 :args (s/cat
        :project-id ::sc/project-id
        :fields (s/keys :opt-un
                        [::snt/name ::snt/description
                         ::snt/max-length ::snt/ordering]))
 :ret ::snt/project-note)

(defn project-notes
  "Returns a vector with all `project-note` entries for the project."
  [project-id]
  (let [project-id (q/to-project-id project-id)]
    (with-project-cache
      project-id [:notes :all]
      (->> (-> (q/select-project-where [:= :p.project-id project-id] [:pn.*])
               (q/with-project-note)
               do-query)
           (group-by :name)
           (map-values first)))))
;;
(s/fdef project-notes
        :args (s/cat :project-id ::sc/project-id)
        :ret ::snt/project-notes-map)

(defn project-settings
  "Returns the current settings map for the project."
  [project-id]
  (let [project-id (q/to-project-id project-id)]
    (-> (q/select-project-where [:= :p.project-id project-id] [:settings])
        do-query first :settings)))
;;
(s/fdef project-settings
        :args (s/cat :project-id ::sc/project-id)
        :ret ::sp/settings)

(defn project-member-article-notes
  "Returns a map of article notes saved by `user-id` in `project-id`."
  [project-id user-id]
  (let [project-id (q/to-project-id project-id)
        user-id (q/to-user-id user-id)]
    (with-project-cache
      project-id [:users user-id :notes]
      (->>
       (-> (q/select-project-articles
            project-id [:an.article-id :an.content :pn.name])
           (merge-join [:project :p]
                       [:= :p.project-id :a.project-id])
           (q/with-article-note nil user-id)
           do-query)
       (group-by :article-id)
       (map-values (partial group-by :name))
       (map-values (partial map-values (comp :content first)))))))
;;
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
;;
(s/fdef project-user-ids
        :args (s/cat :project-id ::sc/project-id
                     :admin? (s/? (s/nilable boolean?)))
        :ret (s/coll-of map?))

(defn project-id-from-register-hash [register-hash]
  (-> (q/select-project-where true [:project-id :project-uuid])
      (->> do-query
           (filter (fn [{:keys [project-id project-uuid]}]
                     (= register-hash (short-uuid project-uuid))))
           first
           :project-id)))

(defn populate-child-project-articles [parent-id child-id article-uuids]
  (doseq [article-uuid article-uuids]
    (when-let [article (-> (q/select-article-where
                            parent-id [:= :article-uuid article-uuid] [:*]
                            {:include-disabled? true})
                           do-query first)]
      (-> (insert-into :article)
          (values [(-> article
                       (assoc :project-id child-id
                              :parent-article-uuid article-uuid)
                       (dissoc :article-id
                               :article-uuid
                               :duplicate-of)
                       (article-to-sql))])
          do-execute))))

(defn copy-project-keywords [src-project-id dest-project-id]
  (let [src-id-to-name
        (-> (q/select-label-where
             src-project-id true
             [:label-id :name]
             {:include-disabled? true})
            (->> do-query
                 (map (fn [{:keys [label-id name]}]
                        [label-id name]))
                 (apply concat)
                 (apply hash-map)))
        name-to-dest-id
        (-> (-> (q/select-label-where
                 dest-project-id true
                 [:label-id :name]
                 {:include-disabled? true})
                (->> do-query
                     (map (fn [{:keys [label-id name]}]
                            [name label-id]))
                     (apply concat)
                     (apply hash-map))))
        convert-label-id
        #(-> % src-id-to-name name-to-dest-id)
        entries
        (-> (q/select-project-keywords src-project-id [:*])
            (->> do-query
                 (map #(when-let [label-id (convert-label-id (:label-id %))]
                         (-> %
                             (dissoc :keyword-id :label-id :project-id)
                             (assoc :label-id label-id
                                    :project-id dest-project-id)
                             (update :label-value to-jsonb))))
                 (remove nil?)
                 vec))]
    (when-not (empty? entries)
      (-> (insert-into :project-keyword)
        (values entries)
        do-execute))))

(defn copy-project-members [src-project-id dest-project-id &
                            {:keys [user-ids-only admin-members-only]}]
  (doseq [user-id (project-user-ids src-project-id)]
    (when (or (nil? user-ids-only)
              (in? user-ids-only user-id))
      (let [{:keys [permissions]} (project-member src-project-id user-id)]
        (when (or (not admin-members-only)
                  (in? permissions "admin"))
          (add-project-member dest-project-id user-id
                              :permissions permissions))))))

(defn project-exists?
  "Does a project with project-id exist?"
  [project-id]
  (= project-id
     (-> (select :project_id)
         (from [:project :p])
         (where [:= :p.project_id project-id])
         do-query
         first
         :project-id)))
;;
(s/fdef project-exists?
        :args (s/cat :project-id int?)
        :ret boolean?)

(defn project-has-labeled-articles?
  [project-id]
  (boolean (> (-> (select :%count.*)
                  (from :article-label)
                  (where [:in :article_id
                          (-> (select :article_id)
                              (from :article)
                              (where [:= :project_id project-id]))])
                  do-query first :count)
              0)))
;;
(s/fdef project-has-labeled-articles?
        :args (s/cat :project-id int?)
        :ret boolean?)

(defn project-users-info [project-id]
  (with-project-cache
    project-id [:users-info]
    (->> (-> (q/select-project-members project-id [:u.*])
             do-query)
         (group-by :user-id)
         (map-values first)
         (map-values
          #(select-keys % [:user-id :user-uuid :email :verified :permissions])))))

(defn project-pmids
  "Given a project-id, return all PMIDs associated with the project"
  [project-id]
  (->> (-> (select :public_id)
           (from :article)
           (where [:and
                   [:= :project_id project-id]
                   [:= :enabled true]])
           do-query)
       (mapv :public-id)
       (mapv parse-number)
       (filterv (comp not nil?))))

(defn project-url-ids [project-id]
  (-> (select :url-id :user-id :date-created)
      (from [:project-url-id :purl])
      (where [:= :project-id project-id])
      (order-by [:date-created :desc])
      (->> do-query vec)))

(defn project-id-from-url-id [url-id]
  (-> (select :project-id)
      (from [:project-url-id :purl])
      (where [:= :url-id url-id])
      do-query first :project-id))

(defn add-project-url-id
  [project-id url-id & {:keys [user-id]}]
  (try
    (with-transaction
      (-> (delete-from :project-url-id)
          (where [:and
                  [:= :project-id project-id]
                  [:= :url-id url-id]])
          do-execute)
      (-> (insert-into :project-url-id)
          (values [{:project-id project-id
                    :url-id url-id
                    :user-id user-id}])
          do-execute))
    (finally
      (clear-project-cache project-id))))

(defn copy-project-label-defs [src-project-id dest-project-id]
  (let [entries
        (-> (select :*)
            (from [:label :l])
            (where [:= :project-id src-project-id])
            (->> do-query
                 (mapv #(-> %
                            (dissoc :label-id :label-id-local :project-id)
                            (assoc :project-id dest-project-id)))))]
    (-> (insert-into :label)
        (values entries)
        do-execute)))

;; TODO: should copy "Project Documents" files
;; TODO: should copy project sources (not just articles)
(defn clone-project
  "Creates a copy of a project.

  Copies most project definition entries over from the parent project
  (eg. project members, label definitions, keywords)."
  [project-name src-id &
   {:keys [user-ids-only admin-members-only]}]
  (with-transaction
    (let [dest-id
          (:project-id (create-project project-name
                                       :parent-project-id src-id))
          article-uuids
          (-> (q/select-project-articles src-id [:a.article-uuid])
              (->> do-query (map :article-uuid)))]
      (add-project-note dest-id {})
      (println (format "created project (#%d, '%s')"
                       dest-id project-name))
      (populate-child-project-articles
       src-id dest-id article-uuids)
      (println (format "loaded %d articles"
                       (project-article-count dest-id)))
      (copy-project-label-defs src-id dest-id)
      (copy-project-keywords src-id dest-id)
      (copy-project-members src-id dest-id
                            :user-ids-only user-ids-only
                            :admin-members-only admin-members-only)))
  (println "clone-project done"))
