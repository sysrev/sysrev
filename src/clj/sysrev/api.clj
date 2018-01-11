(ns sysrev.api
  ^{:doc "An API for generating response maps that are common to /api/* and web-api/* endpoints"}
  (:require [clojure.spec.alpha :as s]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.core :as sc]))

(defn create-project-for-user!
  "Create a new project for user-id using project-name and insert a minimum label, returning the project in a response map"
  [project-name user-id]
  (let [{:keys [project-id] :as project}
        (project/create-project project-name)]
    (labels/add-label-entry-boolean
     project-id {:name "overall include"
                 :question "Include this article?"
                 :short-label "Include"
                 :inclusion-value true
                 :required true})
    (project/add-project-note project-id {})
    (project/add-project-member project-id user-id
                                :permissions ["member" "admin"])
    {:result
     {:success true
      :project (select-keys project [:project-id :name])}}))

(s/fdef create-project-for-user!
        :args (s/cat :project-name ::sp/name
                     :user-id ::sc/user-id)
        :ret ::sp/project)

(defn delete-project!
  "Delete a project with project-id by user-id, returning ???. Checks to ensure the user is an admin of that account"
  [project-id user-id]
  (cond (not (project/member-has-permission? project-id user-id "admin"))
        {:error {:status 403
                 :type :member
                 :message "Not authorized (project)"}}
        (project/member-has-permission? project-id user-id "admin")
        (do (project/delete-project project-id)
            {:result {:success true}})))

;; (defn filtered-project-source-metadata
;;   "Given a project-id, return the vector of metadata with the map corresponding to search-term
;;   removed"
;;   [project-id search-term]
;;   (let []
;;     (->> (project/project-source-metadata project-id)
;;          (filter #(not= (:search-term %) search-term))
;;          (into []))))

;; (s/fdef filtered-project-source-metadata
;;         :args (s/cat :project-id int?
;;                      :search-term string?)
;;         :ret vector?)

;; ;; note: meta data fn's should be move to project eventually
;; (defn set-importing-articles-status!
;;   "Set importing-articles? to a boolean status for a project-id using search-id "
;;   [project-id search-term importing-articles?]
;;   (-> (sqlh/update :project_source)
;;       (sset {:meta (conj (filtered-project-source-metadata project-id search-term)
;;                          {:search-term search-term
;;                           :importing-articles? importing-articles?
;;                           :source "PubMed"})})
;;       (where [:= :project_id project-id])
;;       do-execute))

;; (s/fdef set-importing-articles-status!
;;         :args (s/cat :project-id int?
;;                      :search-term string?
;;                      :importing-articles? boolean?))

;; (defn importing-articles?
;;   "Is the project-id currently importing articles for search-term?"
;;   [project-id search-term]
;;   (let [project-metadata (project/project-source-metadata project-id)
;;         project-source-metadata (filter #(= (:search-term %)) search-term)
;;         importing? (:importing-articles? project-metadata)]
;;     ;; if there is no :importing-articles? meta data, create it
;;     (if (nil? importing?)
;;       (do (set-importing-articles-status! project-id search-term false)
;;           ;; run this fn again
;;           (importing-articles? project-id search-term))
;;       (boolean importing?))))

;; (s/fdef importing-articles?
;;         :args (s/cat :project-id int?
;;                      :search-term string?))
