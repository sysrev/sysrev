(ns sysrev.project.member
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.notifications.core :as notifications]
            [sysrev.project.compensation :as compensation]
            [sysrev.shared.spec.project :as sp]
            [sysrev.util :as util :refer [in? opt-keys]]))

(def valid-permissions ["member" "admin" "owner" "resolve"])

(defn member-roles [project-id user-id]
  (q/find-one :project-member {:user-id user-id :project-id project-id}
              :permissions))

(defn member-role? [project-id user-id test-role]
  (as-> (member-roles project-id user-id) roles
    (when (seq roles)
      (boolean (in? roles test-role)))))

(defn-spec project-member (s/nilable ::sp/project-member)
  [project-id int?, user-id int?]
  (db/with-project-cache project-id [:users user-id :member]
    (q/find-one :project-member {:project-id project-id, :user-id user-id})))

(defn-spec add-project-member nil?
  "Add a user to the list of members of a project."
  [project-id int?, user-id int? &
   {:keys [permissions] :or {permissions ["member"]}}
   (opt-keys ::sp/permissions)]
  (db/with-clear-project-cache project-id
    (q/create :project-member {:project-id project-id, :user-id user-id
                               :permissions (db/to-sql-array "text" permissions)}
              :prepare #(upsert % (-> (on-conflict :project-id :user-id)
                                      (do-update-set :permissions))))
    ;; set their compensation to the project default
    (when-let [comp-id (compensation/get-default-project-compensation project-id)]
      (compensation/start-compensation-period-for-user! comp-id user-id))
    (notifications/subscribe-to-topic
     (notifications/subscriber-for-user
      user-id :create? true :returning :subscriber-id)
     (notifications/topic-for-name
      (str ":project " project-id) :create? true :returning :topic-id))
    (let [[new-user-email] (q/find :web-user {:user-id user-id} :email)
          [project-name] (q/find :project {:project-id project-id} :name)]
      (notifications/create-notification
       {:image-uri (str "/api/user/" user-id "/avatar")
        :new-user-id user-id
        :new-user-name (first (str/split new-user-email #"@"))
        :project-id project-id
        :project-name project-name
        :type :project-has-new-user}))
    nil))

(defn-spec remove-project-member int?
  [project-id int?, user-id int?]
  (db/with-clear-project-cache project-id
    (q/delete :project-member {:project-id project-id :user-id user-id})
    (let [subscriber-id (notifications/subscriber-for-user
                         user-id :returning :subscriber-id)
          topic-id (when subscriber-id
                     (notifications/topic-for-name
                      (str ":project " project-id) :returning :topic-id))]
      (when topic-id
        (notifications/unsubscribe-from-topic subscriber-id topic-id)))))

(defn-spec set-member-permissions (s/nilable (s/coll-of map? :max-count 1))
  [project-id int?, user-id int?,
   permissions (s/and (s/coll-of string? :min-count 1)
                      #(every? (in? valid-permissions) %)) ]
  (db/with-clear-project-cache project-id
    (q/modify :project-member {:project-id project-id :user-id user-id}
              {:permissions (db/to-sql-array "text" permissions)}
              :returning [:user-id :permissions])))



