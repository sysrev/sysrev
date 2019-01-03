(ns sysrev.db.invitation
  (:require [sysrev.db.core :refer [do-query do-execute sql-now]]
            [honeysql.helpers :as sqlh :refer [select from insert-into where values join sset]]
            [honeysql-postgres.helpers :refer [returning]]))

(defn create-invitation!
  "Invite invitee to project-id by inviter. The inviter is the invitee the invitation is from. Optional description is a text field, defaults to 'view-project'."
  [invitee project-id inviter description]
  (let [invitation-id (-> (insert-into :invitation)
                          (values [{:user_id invitee
                                    :project_id project-id
                                    :description description}])
                          (returning :id)
                          do-query
                          first
                          :id)]
    (when-not (nil? invitation-id)
      (-> (insert-into :invitation_from)
          (values [{:invitation_id invitation-id
                    :user_id inviter}])
          do-execute))
    invitation-id))

(defn invitations-for-projects
  "Get all invitations associated projects in coll project-ids"
  [project-ids]
  (if-not (empty? project-ids)
    (-> (select :*)
        (from :invitation)
        (where [:in :project_id project-ids])
        do-query)))

(defn invitations-for-admined-projects
  "Get all invitations that have been sent for projects admined by user-id"
  [user-id]
  (let [admined-projects (-> (select :permissions :project_id)
                             (from :project-member)
                             (where [:= :user-id user-id])
                             do-query
                             (->> (filter #(some (partial = "admin") (:permissions %)))
                                  (map :project-id)))]
    (invitations-for-projects admined-projects)
    ))

(defn invitations-for-user
  "Return all invitations for user-id"
  [user-id]
  (-> (select :i.* [:p.name :project-name])
      (from [:invitation :i])
      (join [:project :p]
            [:= :p.project_id :i.project-id])
      (where [:= :user_id user-id])
      do-query))

(defn update-invitation-accepted!
  [invitation-id accepted?]
  (-> (sqlh/update :invitation)
      (sset {:accepted accepted?
             :updated (sql-now)})
      (where [:= :id invitation-id])
      do-execute))

(defn read-invitation
  "Given an invitation-id, return the invitation"
  [invitation-id]
  (-> (select :*) (from :invitation) (where [:= :id invitation-id]) do-query first))
