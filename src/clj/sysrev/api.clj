(ns sysrev.api
  ^{:doc "An API for generating response maps that are common to /api/* and web-api/* endpoints"}
  (:require [clojure.spec.alpha :as s]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.import.pubmed :as pubmed]
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

;; private fn for now, just because we don't want to actually delete a project, just mark it as inactive
(defn- delete-project!
  "Delete a project with project-id by user-id, returning ???. Checks to ensure the user is an admin of that account"
  [project-id user-id]
  (cond (not (project/member-has-permission? project-id user-id "admin"))
        {:error {:status 403
                 :type :member
                 :message "Not authorized (project)"}}
        (project/member-has-permission? project-id user-id "admin")
        (do (project/delete-project project-id)
            {:result {:success true}})))

(defn import-articles-from-search
  "Import PMIDS resulting from using search-term as a query at source.
  Currently only support PubMed as a source for search queries. Will
  only allow a search-term to be used once for a project. i.e. You
  cannot have multiple 'foo bar' searches for one project over
  multiple dates, but you are allowed multiple search terms for a
  project e.g. 'foo bar' and 'baz qux'"
  [project-id search-term source]
  (let [project-sources (project/project-sources project-id)
        search-term-sources (filter #(= (get-in % [:meta :search-term]) search-term) project-sources)]
    (cond (not (project/project-exists? project-id))
          {:error {:status 403
                   :message "Project does not exist"}}
          ;; there is no import going on for this search-term
          ;; execute it
          (and (empty? search-term-sources)
               (= source "PubMed"))
          (do
            (pubmed/import-pmids-to-project-with-meta!
             (pubmed/get-all-pmids-for-query search-term)
             project-id
             (project/import-pmids-search-term-meta search-term)
             :use-future? true)
            {:result {:success true}})
          (not (empty? search-term-sources))
          {:result {:success true}}
          :else
          {:error {:status 403
                   :message "Unknown event occurred"}})))

(s/fdef import-articles-from-search
        :args (s/cat :project-id int?
                     :search-term string?
                     :source string?)
        :ret map?)

(defn project-sources
  "Return sources for project-id "
  [project-id]
  (if (project/project-exists? project-id)
    {:result {:success true
              :sources (project/project-sources project-id)}}
    {:error {:status 403
             :mesaage "Project does not exist"}}))

(s/fdef project-sources
        :args (s/cat :project-id int?)
        :ret map?)
