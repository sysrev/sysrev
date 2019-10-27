(ns sysrev.project.invitation
  (:require [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as sutil :refer [in?]]))

(defn create-invitation!
  "Invite invitee to project-id by inviter. The inviter is the invitee
  the invitation is from. Optional description is a text field,
  defaults to 'view-project'."
  [invitee project-id inviter description]
  (db/with-transaction
    (when-let [invitation-id (q/create :invitation {:user-id invitee
                                                    :project-id project-id
                                                    :description description}
                                       :returning :id)]
      (q/create :invitation-from {:invitation-id invitation-id
                                  :user-id inviter})
      invitation-id)))

(defn invitations-for-admined-projects
  "Get all invitations that have been sent for projects where `user-id`
  is an admin."
  [user-id]
  (db/with-transaction
    (when-let [project-ids (seq (->> (q/find :project-member {:user-id user-id}
                                             [:permissions :project-id])
                                     (filter #(in? (:permissions %) "admin"))
                                     (map :project-id)))]
      (q/find :invitation {:project-id project-ids}))))

(defn invitations-for-user
  "Returns a sequence of all invitations for `user-id`."
  [user-id]
  (q/find [:invitation :i] {:user-id user-id} [:i.* [:p.name :project-name]]
          :join [:project:p :i.project-id]))

(defn update-invitation-accepted! [invitation-id accepted?]
  (q/modify :invitation {:id invitation-id}
            {:accepted accepted?, :updated :%now}))

(defn get-invitation [invitation-id]
  (q/find-one :invitation {:id invitation-id}))
