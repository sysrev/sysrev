(ns sysrev.db.invitation
  (:require [sysrev.db.core :refer [do-query do-execute sql-now]]
            [honeysql.helpers :as sqlh :refer [select from insert-into where values join sset]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.shared.util :as su :refer [in?]]))

(defn create-invitation!
  "Invite invitee to project-id by inviter. The inviter is the invitee
  the invitation is from. Optional description is a text field,
  defaults to 'view-project'."
  [invitee project-id inviter description]
  (let [invitation-id (-> (insert-into :invitation)
                          (values [{:user-id invitee
                                    :project-id project-id
                                    :description description}])
                          (returning :id)
                          do-query
                          first
                          :id)]
    (when-not (nil? invitation-id)
      (-> (insert-into :invitation-from)
          (values [{:invitation-id invitation-id
                    :user-id inviter}])
          do-execute))
    invitation-id))

(defn invitations-for-projects
  "Get all invitations associated projects in coll project-ids"
  [project-ids]
  (if-not (empty? project-ids)
    (-> (select :*)
        (from :invitation)
        (where [:in :project-id project-ids])
        do-query)))

(defn invitations-for-admined-projects
  "Get all invitations that have been sent for projects admined by user-id"
  [user-id]
  (let [admined-projects (-> (select :permissions :project-id)
                             (from :project-member)
                             (where [:= :user-id user-id])
                             do-query
                             (->> (filter #(in? (:permissions %) "admin"))
                                  (map :project-id)))]
    (invitations-for-projects admined-projects)))

(defn invitations-for-user
  "Return all invitations for user-id"
  [user-id]
  (-> (select :i.* [:p.name :project-name])
      (from [:invitation :i])
      (join [:project :p]
            [:= :p.project-id :i.project-id])
      (where [:= :user-id user-id])
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
