(ns sysrev.project.core
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [honeysql.helpers
             :as sqlh
             :refer [from join merge-join select sset where]]
            [medley.core :as medley]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core
             :as db
             :refer [do-query with-project-cache
                     with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.shared.keywords :refer [canonical-keyword]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.keywords :as skw]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.shared.spec.notes :as snt]
            [sysrev.shared.spec.project :as sp]
            [sysrev.util
             :as
             util
             :refer
             [index-by map-values opt-keys]]))

;; for clj-kondo
(declare delete-project)

(s/def ::include-disabled? (s/nilable boolean?))

(def default-project-settings {:second-review-prob 0.5
                               :public-access true})

(s/def ::parent-project-id ::sp/project-id)

(defn-spec create-project ::sp/project
  [project-name string? &
   {:keys [parent-project-id]} (opt-keys ::parent-project-id)]
  (q/create :project {:name project-name
                      :enabled true
                      :project-uuid (random-uuid)
                      :settings (db/to-jsonb default-project-settings)
                      :parent-project-id parent-project-id}
            :returning :*))

(defn-spec delete-project int?
  "Deletes a project entry. All dependent entries should be deleted also by
  ON DELETE CASCADE constraints in Postgres."
  [project-id int?]
  (db/with-clear-project-cache project-id
    ;; delete the project sources first, to prevent deadlock errors during parallel tests on the remote build server
    (q/delete :project-source {:project-id project-id})
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
  (db/with-clear-project-cache project-id
    (let [cur-settings (-> (select :settings)
                           (from :project)
                           (where [:= :project-id project-id])
                           do-query first :settings)
          new-settings (assoc cur-settings setting new-value)]
      (assert (s/valid? ::sp/settings new-settings))
      (-> (sqlh/update :project)
          (sset {:settings (db/to-jsonb new-settings)})
          (where [:= :project-id project-id])
          db/do-execute)
      new-settings)))

(defn change-project-name [project-id project-name]
  (assert (string? project-name))
  (db/with-clear-project-cache project-id
    (-> (sqlh/update :project)
        (sset {:name project-name})
        (where [:= :project-id project-id])
        db/do-execute)
    project-name))

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
  [project-id int? & [include-disabled] (s/? any?)]
  (with-project-cache project-id [:labels :all include-disabled]
    (let [check-enabled #(if include-disabled % (merge % {:enabled true}))
          labels (q/find :label (check-enabled {:project-id project-id
                                                :root-label-id-local nil})
                         :*, :index-by :label-id, :where [:!= :value-type "group"])
          group-labels (->> (q/find :label (check-enabled {:project-id project-id
                                                           :value-type "group"})
                                    :*, :index-by :label-id)
                            (map-values
                             (fn [{:keys [label-id-local] :as g-label}]
                               (assoc g-label :labels
                                      (q/find :label (check-enabled
                                                      {:project-id project-id
                                                       :root-label-id-local label-id-local})
                                              :*, :index-by :label-id)))))]
      (merge labels group-labels))))

(defn-spec project-consensus-label-ids (s/nilable (s/coll-of ::sc/label-id))
  [project-id int? &
   [include-disabled] (s/cat :include-disabled (s/? (s/nilable boolean?)))]
  (with-project-cache project-id [:labels :consensus include-disabled]
    (q/find-label {:project-id project-id :consensus true} :label-id
                  :include-disabled include-disabled)))

(defn-spec project-overall-label-id (s/nilable ::sl/label-id)
  [project-id int?]
  (with-project-cache project-id [:labels :overall-label-id]
    (q/find-label-1 {:project-id project-id :name "overall include"} :label-id)))

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

(defn-spec add-project-keyword map?
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
    (->> (q/find :project-keyword {:project-id project-id} :*)
         (map #(assoc % :toks (->> (str/split (:value %) #" ")
                                   (mapv canonical-keyword))))
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
                 :join [[:project-note :pn] :p.project-id])
         (index-by :name))))

(defn-spec project-settings ::sp/settings
  "Returns the current settings map for the project."
  [project-id int?]
  (with-project-cache project-id [:settings]
    (q/find-one :project {:project-id project-id} :settings)))

(defn-spec project-user-ids (s/or :ids (s/nilable (s/coll-of int?))
                                  :query map?)
  "Returns sequence of user-id for all members of project."
  [project-id int? &
   {:keys [return] :or {return :execute}} (opt-keys ::q/return)]
  (q/find :project-member {:project-id project-id} :user-id :return return))

(defn project-id-from-register-hash [register-hash]
  (->> (q/find :project {} [:project-id :project-uuid])
       (filter #(= register-hash (-> % :project-uuid util/short-uuid)))
       first :project-id))

(defn-spec project-exists? boolean?
  "Does a project with project-id exist?"
  [project-id int? &
   {:keys [include-disabled?] :or {include-disabled? true}}
   (opt-keys ::include-disabled?)]
  (with-project-cache project-id [:exists?]
    (= project-id (q/find-one :project (cond-> {:project-id project-id}
                                         (not include-disabled?) (assoc :enabled true))
                              :project-id))))

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

(defn project-url-ids [project-id]
  (vec (q/find :project-url-id {:project-id project-id} [:url-id :user-id :date-created]
               :order-by [:date-created :desc])))

(defn project-id-from-url-id [url-id]
  (or (util/parse-integer url-id)
      (first (q/find :project-url-id {:url-id url-id} :project-id))))

(defn member-count [project-id]
  (q/find-count :project-member {:project-id project-id}))

(defn project-article-ids
  "Returns list of all article ids in project. enabled may optionally be
  passed as true or false to filter by enabled status."
  [project-id & [enabled]]
  (assert (contains? #{nil true false} enabled))
  (q/find :article (cond-> {:project-id project-id}
                     (boolean? enabled) (merge {:enabled enabled}))
          :article-id))

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
    (if-let [{:keys [group-id group-name] :as _owner}
             (q/find-one [:project-group :pg] {:project-id project-id}
                         [:g.group-id :g.group-name]
                         :join [[:groups :g] :pg.group-id])]
      {:group-id group-id :name group-name}
      (when-let [{:keys [user-id email] :as _owner}
                 (q/find-one [:project-member :pm] {:pm.project-id project-id
                                                    "owner" :%any.pm.permissions}
                             [:u.user-id :u.email]
                             :join [[:web-user :u] :pm.user-id])]
        {:user-id user-id :name (-> email (str/split #"@") first)}))))

(defn all-project-owners []
  (with-transaction
    (merge (->> (q/find [:project-member :pm] {"owner" :%any.pm.permissions}
                        [:u.user-id :u.email]
                        :join [[:web-user :u] :pm.user-id], :index-by :project-id :limit 1)
                (map-values #(-> (assoc % :name (-> (:email %) (str/split #"@") first))
                                 (dissoc :email))))
           (q/find [:project-group :pg] {}
                   [:g.group-id [:g.group-name :name]]
                   :join [[:groups :g] :pg.group-id], :index-by :project-id))))

(defn all-public-projects []
  (with-transaction
    (let [owners (all-project-owners)]
      (->> (q/find-project {} [:project-id :name :settings])
           (filter #(-> % :settings :public-access true?))
           (map #(dissoc % :settings))
           (map #(assoc % :owner (get owners (:project-id %))))))))

(defn last-active
  "When was the last time an article-label was updated for project-id?"
  [project-id]
  (first (q/find [:article :a] {:a.project-id project-id} :al.updated-time
                 :join [[:article-label :al] :a.article-id]
                 :order-by [[:al.updated-time :desc] [:al.article-id :desc]]
                 :limit 1)))

(defn search-projects-important-terms
  "Return a list of projects containing important terms in the given query"
  [q & {:keys [limit] :or {limit 10}}]
  (let [tokens (str/split q #" ")]
    (-> (select [:md.string :description] :p.project_id :p.name :p.settings)
        (from [::important-terms :it])
        (sqlh/join
         [:project-important-terms :pit] [:= :pit.term-id :it.term-id])
        (sqlh/left-join
         [:project_description :pd] [:= :pd.project-id :pit.project-id]
         [:markdown :md] [:= :md.markdown-id :pd.markdown-id]
         [:project :p] [:= :p.project-id :pit.project-id])
        (where [:in :it.term tokens])
        db/do-query)))

;; some notes:
;; https://www.postgresql.org/docs/current/textsearch.html
;; https://www.postgresql.org/docs/current/textsearch-controls.html
;; https://stackoverflow.com/questions/44285327/postgres-tsvector-with-relational-tables
;; https://dba.stackexchange.com/questions/15412/postgres-full-text-search-with-multiple-columns-why-concat-in-index-and-not-at?rq=1
;; https://dba.stackexchange.com/questions/107801/full-text-search-on-multiple-joined-tables?noredirect=1#comment196534_107801
;; https://dba.stackexchange.com/questions/108005/full-text-search-on-two-tsvector-columns
(defn search-projects
  "Return a list of projects using the search query q"
  [q & {:keys [limit] :or {limit 10}}]
  (->> [(str "SELECT md.string as description, p.project_id, p.name, p.settings "
             "FROM project_description pd "
             "RIGHT JOIN project p on p.project_id = pd.project_id "
             "LEFT JOIN markdown md on md.markdown_id = pd.markdown_id "
             "WHERE p.enabled = true AND p.settings->>'public-access' = 'true' "
             "AND to_tsvector('english', coalesce(md.string,'') || ' ' || "
             "                           (coalesce(p.name,''))) @@ plainto_tsquery('english', ?) "
             "LIMIT ? ")
        q limit]
       db/raw-query
       (concat (search-projects-important-terms q :limit limit))
       distinct))

(defn project-admin-or-owner?
  "Is user-id an owner or admin of project-id?"
  [user-id project-id]
  (->> (conj (get-project-admins project-id)
             (get-project-owner project-id))
       (medley/find-first #(= (:user-id %) user-id))
       boolean))
