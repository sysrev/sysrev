(ns sysrev.project-api.core
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [medley.core :as medley]
            [sysrev.lacinia.interface :as sl
             :refer [execute-one! with-tx-context]]
            [sysrev.postgres.interface :as pg]))

(defn bearer-token [context]
  (some-> context :request :headers (get "authorization")
          (->> (re-matches #"(?i)Bearer (.*)"))
          second))

(defn current-user-id [context]
  (when-let [token (bearer-token context)]
    (with-tx-context [context context]
      (-> context
          (execute-one! {:select :user-id
                         :from :web-user
                         :where [:= :api-token token]})
          :web-user/user-id))))

(defn project-permissions-for-user [context ^Long project-id ^Long user-id]
  (when user-id
    (some-> (execute-one!
             context
             {:select :permissions
              :from :project-member
              :where [:and
                      :enabled
                      [:= :project-id project-id]
                      [:= :user-id user-id]]})
            :project-member/permissions .getArray set)))

(defn project-admin? [perms]
  (boolean
   (when perms
     (or (perms "admin") (perms "owner")))))

(defn project-member? [perms]
  (boolean
   (when perms
     (or (perms "member") (perms "admin") (perms "owner")))))

(defn public-project? [context ^Long project-id]
  (-> context
      (execute-one!
       {:select [[[:cast [(keyword "->>") :settings "public-access"] :boolean] :public]]
        :from :project
        :where [:= :project-id project-id]})
      :public
      boolean))

(defn check-not-blank [s name]
  (when (and (not (keyword? s)) (or (not s) (str/blank? s)))
    (resolve/resolve-as nil {:message (str name " field cannot be blank")})))

(defn token-check [user-id]
  (when-not user-id
    (resolve/resolve-as nil {:message "Invalid API token"})))

(defn project-admin-check [context ^Long project-id ^Long user-id]
  (when-not (and project-id
                 (project-admin?
                  (project-permissions-for-user context project-id user-id)))
    (resolve/resolve-as nil {:message "Must be a project admin"})))

(def project-cols
  {:created :date-created
   :id :project-id
   :name :name
   :public :settings})

(def project-cols-inv (-> project-cols (dissoc :public) sl/invert))

(defn get-project [context {:keys [id]} _]
  (when-let [int-id (sl/parse-int-id id)]
    (with-tx-context [context context]
      (let [user-id (current-user-id context)
            ks (sl/current-selection-names context)
            perms (project-permissions-for-user context int-id user-id)
            {:project/keys [settings] :as project}
            #__ (execute-one! context {:select (keep project-cols ks)
                                       :from :project
                                       :where [:and
                                               [:= :project-id int-id]
                                               (when-not (project-member? perms)
                                                 [:raw "cast(settings->>'public-access' as boolean) = true"])]})]
        (some-> project
                (->> (sl/remap-keys #(or (project-cols-inv %) %)))
                (update :id str)
                (cond-> (:public ks) (assoc :public (-> settings :public-access boolean))))))))

(def default-inclusion-label
  {:category "inclusion criteria"
   :consensus true
   :definition (pg/jsonb-pgobject {:inclusion-values [true]})
   :enabled true
   :name "overall include"
   :question "Include this article?"
   :short-label "Include"
   :required true
   :value-type "boolean"})

(defn create-project! [context args _]
  (with-tx-context [context context]
    (let [user-id (current-user-id context)
          {:keys [name public]} (get-in args [:input :create])]
      (cond
        (not user-id) (resolve/resolve-as nil {:message "Invalid API token"})
        (str/blank? name) (resolve/resolve-as nil {:message "Project name cannot be blank"
                                                   :name name})
        :else
        (let [settings {:public-access public
                        :second-review-prob 0.5}
              project-id (-> context
                             (execute-one! {:insert-into :project
                                            :values [{:name name
                                                      :settings (pg/jsonb-pgobject settings)}]
                                            :returning :project-id})
                             :project/project-id)
              label-id (random-uuid)]
          (execute-one! context
                        {:insert-into :label
                         :values [(assoc default-inclusion-label
                                         :global-label-id label-id
                                         :label-id label-id
                                         :owner-project-id project-id
                                         :project-id project-id)]})
          (execute-one! context
                        {:insert-into :project-member
                         :values [{:permissions [:array ["owner" "admin" "member"]]
                                   :project-id project-id
                                   :user-id user-id}]})
          {:id (str project-id)})))))

(defn resolve-create-project-payload#project [context _ val]
  (get-project context val nil))

;; NOTE: name column is not Name. short-label is Name.
(def project-label-cols
  {:consensus :consensus
   :id :label-id
   :enabled :enabled
   :name :short-label
   :project :project-id
   :question :question
   :required :required
   :type :value-type})

(def project-label-cols-inv (-> project-label-cols sl/invert))

(defn get-project-label [context {:keys [id]} _]
  (when-let [uuid (and id (parse-uuid id))]
    (with-tx-context [context context]
      (let [user-id (current-user-id context)
            ks (sl/current-selection-names context)
            {:label/keys [project-id] :as project-label}
            #__ (execute-one!
                 context
                 {:select (keep project-label-cols (conj ks :project))
                  :from :label
                  :where [:= :label-id uuid]})]
        (when (or (public-project? context project-id)
                  (project-member? (project-permissions-for-user context project-id user-id)))
          (some-> project-label
                  (->> (sl/remap-keys #(or (project-label-cols-inv %) %)))
                  (assoc :id id)))))))

(defn resolve-project-label#project [_ _ _]
  nil)

(defn random-id
  "Generate a random string id from uppercase/lowercase letters"
  ([len]
   (let [length (or len 6)
         char-gen (gen/fmap char (gen/one-of [(gen/choose 65 90)
                                              (gen/choose 97 122)]))]
     (apply str (gen/sample char-gen length))))
  ([] (random-id 6)))

(defn random-label-name
  "For compatibility with sysrev code.

  This is for the name column, not the name field, which is the short-label column."
  [value-type]
  (str (str/lower-case value-type) (random-id)))

(defn create-project-label!
  [context {{:keys [create projectId]} :input} _]
  (with-tx-context [context context]
    (let [user-id (current-user-id context)
          {:keys [consensus enabled name question required type]
           :or {consensus false
                enabled true
                required false}}
          #__ create
          project-id (parse-long projectId)]
      (or
       (check-not-blank name "name")
       (check-not-blank question "question")
       (check-not-blank type "type")
       (token-check user-id)
       (project-admin-check context project-id user-id)
       (let [id (random-uuid)
             value-type (#'name type)]
         {:id (str id)}
         (execute-one!
          context
          {:insert-into :label
           :values [{:consensus consensus
                     :enabled enabled
                     :global-label-id id
                     :label-id id
                     :name (random-label-name value-type)
                     :owner-project-id project-id
                     :project-id project-id
                     :question question
                     :required required
                     :short-label name
                     :value-type value-type}]})
         {:id (str id)})))))

(defn resolve-create-project-label-payload#project-label [context _ val]
  (get-project-label context val nil))

(defn resolve-project#labels [_ _ _])

(def resolvers
  {:CreateProjectLabelPayload {:projectLabel #'resolve-create-project-label-payload#project-label}
   :CreateProjectPayload {:project #'resolve-create-project-payload#project}
   :Project {:labels #'resolve-project#labels}
   :ProjectLabel {:project #'resolve-project-label#project}
   :Query {:getProject #'get-project
           :getProjectLabel #'get-project-label}
   :Mutation {:createProject #'create-project!
              :createProjectLabel #'create-project-label!}})
