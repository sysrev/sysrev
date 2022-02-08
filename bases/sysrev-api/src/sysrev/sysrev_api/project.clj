(ns sysrev.sysrev-api.project
  (:require
   [clojure.string :as str]
   [com.walmartlabs.lacinia.resolve :as resolve]
   [medley.core :as medley]
   [sysrev.lacinia.interface :as sl]
   [sysrev.postgres.interface :as pg]
   [sysrev.sysrev-api.core :as core
    :refer [execute-one! with-tx-context]]
   [sysrev.sysrev-api.user :as user])
  (:import
   (java.util UUID)))

;; Remove in Clojure 1.11
(defn parse-long [s]
  (Long/valueOf s))

(def project-cols
  {:created :date-created
   :id :project-id
   :name :name
   :public :settings})

(def project-cols-inv (-> project-cols (dissoc :public) core/inv-cols))

(defn resolve-project [context {:keys [id]} _]
  (when-let [int-id (when (re-matches core/re-int-id id) (parse-long id))]
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

(defn createProject! [context {{:keys [name public]} :input} _]
  (with-tx-context [context context]
    (let [user-id (user/current-user-id context)]
      (if-not user-id
        (resolve/resolve-as nil {:message "Invalid API token"})
        (let [settings {:public-access public
                        :second-review-prob 0.5}
              project-id (-> context
                             (execute-one! {:insert-into :project
                                            :values [{:name name
                                                      :settings (pg/jsonb-pgobject settings)}]
                                            :returning :project-id})
                             :project/project-id)
              label-id (UUID/randomUUID)]
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
          (resolve-project context {:id (str project-id)} nil))))))
