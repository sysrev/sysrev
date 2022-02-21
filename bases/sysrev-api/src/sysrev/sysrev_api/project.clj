(ns sysrev.sysrev-api.project
  (:require
   [clojure.string :as str]
   [com.walmartlabs.lacinia.resolve :as resolve]
   [medley.core :as medley]
   [sysrev.lacinia.interface :as sl]
   [sysrev.postgres.interface :as pg]
   [sysrev.sysrev-api.core :as core
    :refer [execute-one! with-tx-context]]
   [sysrev.sysrev-api.user :as user]))

(def project-cols
  {:created :date-created
   :id :project-id
   :name :name
   :public :settings})

(def project-cols-inv (-> project-cols (dissoc :public) core/inv-cols))

(defn get-project [context {:keys [id]} _]
  (when-let [int-id (sl/parse-int-id id)]
    (with-tx-context [context context]
      (let [user-id (user/current-user-id context)
            ks (sl/current-selection-names context)
            {:project/keys [settings] :as project}
            #__ (execute-one! context {:select (keep project-cols ks)
                                       :from :project
                                       :where [:and
                                               [:= :project-id int-id]
                                               [:or
                                                [:raw "cast(settings->>'public-access' as boolean) = true"]
                                                [:in user-id {:select :user-id
                                                              :from :project-member
                                                              :where [:and
                                                                      :enabled
                                                                      [:= :project-id int-id]
                                                                      [:= :user-id user-id]
                                                                      [:or
                                                                       [:= "owner" [:any :permissions]]
                                                                       [:= "admin" [:any :permissions]]
                                                                       [:= "member" [:any :permissions]]]]}]]]})]
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
    (let [user-id (user/current-user-id context)
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
                                         :project-id project-id)]})
          (execute-one! context
                        {:insert-into :project-member
                         :values [{:permissions [:array ["owner" "admin" "member"]]
                                   :project-id project-id
                                   :user-id user-id}]})
          {:project (str project-id)})))))
