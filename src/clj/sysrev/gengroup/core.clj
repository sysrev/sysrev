(ns sysrev.gengroup.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [honeysql.helpers :as sqlh]
            [sysrev.db.core :as db :refer [with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.util :as util :refer [index-by]]))

(defn create-gengroup! [gengroup-name gengroup-description]
  (q/create :gengroup {:name gengroup-name :description gengroup-description}
            :returning :gengroup-id))

(defn read-gengroup [gengroup-id]
  (q/find-one :gengroup {:gengroup-id gengroup-id}))

(defn update-gengroup! [gengroup-id gengroup-name gengroup-description]
  (q/modify :gengroup {:gengroup-id gengroup-id}
            {:name gengroup-name :description gengroup-description :updated :%now}))

(defn delete-gengroup! [gengroup-id]
  (q/delete :gengroup {:gengroup-id gengroup-id}))

(defn create-project-member-gengroup! [project-id gengroup-name gengroup-description]
  (with-transaction
    (let [gengroup-id (create-gengroup! gengroup-name gengroup-description)]
      (q/create :project-member-gengroup {:project-id project-id :gengroup-id gengroup-id}
                :returning :id))))

(defn delete-project-member-gengroup! [project-id gengroup-id]
  (with-transaction
    (q/delete :project-member-gengroup {:project-id project-id :gengroup-id gengroup-id}) 
    (delete-gengroup! gengroup-id)))

(defn read-project-member-gengroups [project-id & {:keys [gengroup-name]}]
  (let [extra {:g.name gengroup-name}
        query (into {:pmg.project-id project-id}
                    ;filter nil values
                    (filter second extra))]
    (q/find [:project-member-gengroup :pmg] query
            [:pmg.project-id :g.name :g.description]
            :join [[:gengroup :g] :pmg.gengroup-id])))

(defn project-member-gengroup-add [project-id gengroup-id membership-id]
  (q/create :project-member-gengroup-member {:project-id project-id :gengroup-id gengroup-id :membership-id membership-id}))

(defn project-member-gengroup-remove [project-id gengroup-id membership-id]
  (q/delete :project-member-gengroup-member {:project-id project-id :gengroup-id gengroup-id :membership-id membership-id}))

;(create-project-member-gengroup! 42884 "English" "This is for the english language")

;(read-project-member-gengroups 42884 :gengroup-name "test")

;(project-member-gengroup-add 42884 3 45083)

;(project-member-gengroup-remove 42884 2 45083)

;(update-gengroup! 4 "test3" "")
