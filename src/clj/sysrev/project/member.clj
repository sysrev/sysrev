(ns sysrev.project.member
  (:require [clojure.spec.alpha :as s]
            [honeysql-postgres.helpers :refer [upsert on-conflict do-update-set]]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
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
    nil))

(defn-spec remove-project-member int?
  [project-id int?, user-id int?]
  (db/with-clear-project-cache project-id
    (q/delete :project-member {:project-id project-id :user-id user-id})))

(defn-spec set-member-permissions (s/nilable (s/coll-of map? :max-count 1))
  [project-id int?, user-id int?,
   permissions (s/and (s/coll-of string? :min-count 1)
                      #(every? (in? valid-permissions) %)) ]
  (db/with-clear-project-cache project-id
    (q/modify :project-member {:project-id project-id :user-id user-id}
              {:permissions (db/to-sql-array "text" permissions)}
              :returning [:user-id :permissions])))

(defn project-members [project-id]
  (->>
    (q/find [:project-member :pm] {:pm.project-id project-id}
            [:pm.membership-id :wu.email [:g.name :gengroup-name] [:g.gengroup-id :gengroup-id]]
            :join [[[:web-user :wu] :pm.user-id]]
            :left-join [[[:project-member-gengroup-member :pmgm] [:and
                                                                  [:= :pmgm.project-id :pm.project-id]
                                                                  [:= :pmgm.membership-id :pm.membership-id]
                                                                  ]]
                        [[:gengroup :g] :pmgm.gengroup-id]])

    ;; TODO: fix this poor man's SQL group-by
    (group-by :membership-id)
    (map (fn [[membership-id items]]
           (let [gengroups (->> items
                                (filter :gengroup-id )
                                (map #(select-keys % [:gengroup-name :gengroup-id]))
                                vec)]
             (-> (first items)
                 (dissoc :gengroup-name)
                 (dissoc :gengroup-id)
                 (assoc :gengroups gengroups)))))))

