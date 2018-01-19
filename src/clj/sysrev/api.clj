(ns sysrev.api
  ^{:doc "An API for generating response maps that are common to /api/* and web-api/* endpoints"}
  (:require [clojure.spec.alpha :as s]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.db.core :as db]
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

(defn delete-project!
  "Delete a project with project-id by user-id. Checks to ensure the user is an admin of that project"
  [project-id user-id]
  (cond (not (project/member-has-permission? project-id user-id "admin"))
        {:error {:status 403
                 :type :member
                 :message "Not authorized (project)"}}
        (project/project-has-labeled-articles? project-id)
        ;; eventually, we will disable the project in this case
        {:error {:status 403
                 :message "Project contains reviewed articles"}}
        (project/member-has-permission? project-id user-id "admin")
        (do (project/delete-project project-id)
            {:result {:success true}})))

(s/fdef delete-project!
        :args (s/cat :project-id int?
                     :user-id int?)
        :ret map?)

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
             :use-future? (nil? db/*conn*))
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

(defn import-articles-from-file
  "Import PMIDs into project-id from file. A file is a white-space/comma separated file of PMIDs. Only one import from a file is allowed at one time"
  [project-id file filename]
  (let [project-sources (project/project-sources project-id)
        filename-sources (filter #(= (get-in % [:meta :filename]) filename) project-sources)]
    (try
      (let [pmid-vector (pubmed/parse-pmid-file file)]
        (cond (not (project/project-exists? project-id))
              {:error {:status 403
                       :message "Project does not exist"}}
              ;; there is no import going on for this search-term
              ;; execute it
              (and (empty? filename-sources))
              (do
                (pubmed/import-pmids-to-project-with-meta!
                 pmid-vector
                 project-id
                 (project/import-pmids-from-filename-meta filename)
                 :use-future? (nil? db/*conn*))
                {:result {:success true}})
              (not (empty? filename-sources))
              {:result {:success true}}
              :else
              {:error {:status 403
                       :message "Unknown event occurred"}}))
      (catch Throwable e
        {:error {:status 403
                 :message "Error parsing file"}}))))


(defn project-sources
  "Return sources for project-id"
  [project-id]
  (if (project/project-exists? project-id)
    {:result {:success true
              :sources (project/project-sources project-id)}}
    {:error {:status 403
             :mesaage "Project does not exist"}}))

(s/fdef project-sources
        :args (s/cat :project-id int?)
        :ret map?)

(defn delete-source!
  "Delete a source with source-id by user-id."
  [source-id]
  (cond (project/source-has-labeled-articles? source-id)
        {:error {:status 403
                 :message "Source contains reviewed articles"}}
        :else (do (project/delete-project-source! source-id)
                  {:result {:success true}})))

(s/fdef delete-source!
        :args (s/cat :source-id int?)
        :ret map?)
