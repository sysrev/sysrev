(ns sysrev.api
  ^{:doc "An API for generating response maps that are common to /api/* and web-api/* endpoints"}
  (:require [clojure.spec.alpha :as s]
            [sysrev.db.core :as db]
            [sysrev.db.labels :as labels]
            [sysrev.db.project :as project]
            [sysrev.db.sources :as sources]
            [sysrev.import.endnote :as endnote]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.shared.spec.project :as sp]
            [sysrev.shared.spec.core :as sc]))

;; Error code used
(def forbidden 403)
(def not-found 404)
(def internal-server-error 500)

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
  "Delete a project with project-id by user-id. Checks to ensure the user is an admin of that project. If there are reviewed articles in the project, disables project instead of deleting it"
  [project-id user-id]
  (cond (not (project/member-has-permission? project-id user-id "admin"))
        {:error {:status forbidden
                 :type :member
                 :message "Not authorized (project)"}}

        (project/project-has-labeled-articles? project-id)
        (do (project/disable-project! project-id)
            {:result {:success true
                      :project-id project-id}})

        (project/member-has-permission? project-id user-id "admin")
        (do (project/delete-project project-id)
            {:result {:success true
                      :project-id project-id}})))

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
  [project-id search-term source & {:keys [threads] :or {threads 1}}]
  (let [project-sources (sources/project-sources project-id)
        search-term-sources (filter #(= (get-in % [:meta :search-term]) search-term) project-sources)]
    (cond (not (project/project-exists? project-id))
          {:error {:status not-found
                   :message "Project does not exist"}}

          ;; there is no import going on for this search-term
          ;; execute it
          (and (empty? search-term-sources)
               (= source "PubMed"))
          (try
            (let [pmids (pubmed/get-all-pmids-for-query search-term)
                  meta (sources/import-pmids-search-term-meta search-term
                                                              (count pmids))
                  success?
                  (pubmed/import-pmids-to-project-with-meta!
                   pmids project-id meta
                   :use-future? true
                   :threads threads)]
              (if success?
                {:result {:success true}}
                {:error {:status internal-server-error
                         :message "Error during import (1)"}}))
            (catch Throwable e
              {:error {:status internal-server-error
                       :message "Error during import (2)"}}))

          (not (empty? search-term-sources))
          {:result {:success true}}

          :else
          {:error {:status internal-server-error
                   :message "Unknown event occurred"}})))

(s/def ::threads integer?)

(s/fdef import-articles-from-search
        :args (s/cat :project-id int?
                     :search-term string?
                     :source string?
                     :keys (s/keys* :opt-un [::threads]))
        :ret map?)

(defn import-articles-from-file
  "Import PMIDs into project-id from file. A file is a white-space/comma separated file of PMIDs. Only one import from a file is allowed at one time"
  [project-id file filename & {:keys [threads] :or {threads 1}}]
  (let [project-sources (sources/project-sources project-id)
        filename-sources (filter #(= (get-in % [:meta :filename]) filename)
                                 project-sources)]
    (try
      (let [pmid-vector (pubmed/parse-pmid-file file)]
        (cond (empty? pmid-vector)
              {:error {:status internal-server-error
                       :message "Error parsing file"}}

              (not (project/project-exists? project-id))
              {:error {:status not-found
                       :message "Project does not exist"}}

              ;; there is no import going on for this filename
              (and (empty? filename-sources))
              (try
                (let [meta (sources/import-pmids-from-filename-meta filename)
                      success?
                      (pubmed/import-pmids-to-project-with-meta!
                       pmid-vector project-id meta
                       :use-future? true
                       :threads threads)]
                  (if success?
                    {:result {:success true}}
                    {:error {:status internal-server-error
                             :message "Error during import (1)"}}))
                (catch Throwable e
                  {:error {:status internal-server-error
                           :message "Error during import (2)"}}))

              (not (empty? filename-sources))
              {:result {:success true}}

              :else
              {:error {:status forbidden
                       :message "Unknown event occurred"}}))
      (catch Throwable e
        {:error {:status internal-server-error
                 :message "Error parsing file"}}))))

(defn import-articles-from-endnote-file
  "Import PMIDs into project-id from file. A file is a white-space/comma separated file of PMIDs. Only one import from a file is allowed at one time"
  [project-id file filename & {:keys [threads] :or {threads 1}}]
  (let [project-sources (sources/project-sources project-id)
        filename-sources (filter #(= (get-in % [:meta :filename]) filename) project-sources)]
    (try
      (cond (not (project/project-exists? project-id))
            {:error {:status not-found
                     :message "Project does not exist"}}
            ;; there is no import going on for this filename
            (and (empty? filename-sources))
            (do
              (future (endnote/import-endnote-library!
                       file
                       filename
                       project-id
                       :use-future? true
                       :threads 3))
              {:result {:success true}})
            (not (empty? filename-sources))
            {:result {:success true}}
            :else
            {:error {:status internal-server-error
                     :message "Unknown event occurred"}})
      (catch Throwable e
        {:error {:status internal-server-error
                 :message (.getMessage e)}}))))

(defn project-sources
  "Return sources for project-id"
  [project-id]
  (if (project/project-exists? project-id)
    {:result {:success true
              :sources (sources/project-sources project-id)}}
    {:error {:status not-found
             :mesaage (str "project-id " project-id  " does not exist")}}))

(s/fdef project-sources
        :args (s/cat :project-id int?)
        :ret map?)

(defn delete-source!
  "Delete a source with source-id by user-id."
  [source-id]
  (cond (sources/source-has-labeled-articles? source-id)
        {:error {:status forbidden
                 :message "Source contains reviewed articles"}}
        (not (sources/source-exists? source-id))
        {:error {:status not-found
                 :message (str "source-id " source-id " does not exist")}}
        :else (do (sources/delete-project-source! source-id)
                  {:result {:success true}})))

(s/fdef delete-source!
        :args (s/cat :source-id int?)
        :ret map?)

(defn toggle-source!
  "Toggle a source as being enabled or disabled."
  [source-id enabled?]
  (if (sources/source-exists? source-id)
    (do (sources/toggle-source! source-id enabled?)
        {:result {:success true}})
    {:error {:status not-found
             :message (str "source-id " source-id " does not exist")}}))

(s/fdef toggle-source!
        :args (s/cat :source-id int?
                     :enabled? boolean?)
        :ret map?)
