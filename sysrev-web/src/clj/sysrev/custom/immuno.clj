;;
;; This code is mostly specific to the Immunotherapy project.
;;

(ns sysrev.custom.immuno
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.users :as users]
            [sysrev.db.labels :as labels]
            [sysrev.util :refer [xml-find]]
            [clojure.string :as str]
            [sysrev.db.queries :as q]))

(defn parse-endnote-file [fname]
  (-> fname
      io/file io/reader dxml/parse))

(defn match-article-id
  "Attempt to find an article-id in the database which is the best match
  for the given fields. Requires an exact match on title and journal fields,
  allows for differences in remote-database-name and attempts to select an 
  appropriate match."
  [project-id title journal rdb-name]
  (let [results (-> (select :article-id :remote-database-name)
                    (from :article)
                    (where [:and
                            [:= :project-id project-id]
                            [:= :primary-title title]
                            [:= :secondary-title journal]])
                    (order-by :article-id)
                    do-query)]
    (if (empty? results)
      (do (println (format "no article match: '%s' '%s' '%s'"
                           title journal rdb-name))
          nil)
      ;; Attempt a few different ways of finding the best match based
      ;; on remote-database-name.
      (or
       ;; exact match
       (->> results
            (filter #(= (:remote-database-name %) rdb-name))
            first
            :article-id)
       ;; case-insensitive match
       (->> results
            (filter #(and (string? (:remote-database-name %))
                          (string? rdb-name)
                          (= (-> % :remote-database-name str/lower-case)
                             (-> rdb-name str/lower-case))))
            first
            :article-id)
       ;; prefer embase
       (->> results
            (filter #(and (string? (:remote-database-name %))
                          (= (-> % :remote-database-name str/lower-case)
                             "embase")))
            first
            :article-id)
       ;; then prefer medline
       (->> results
            (filter #(and (string? (:remote-database-name %))
                          (= (-> % :remote-database-name str/lower-case)
                             "medline")))
            first
            :article-id)
       ;; otherwise use earliest inserted article match
       (-> results first :article-id)))))

(defn load-endnote-file
  "Parse the Endnote XML file into a map containing the fields we're interested
  in. `group-field` should be a keyword with the name of the Endnote reference
  field used to attach the group name to each entry (defaults to :custom1)."
  [fname & [group-field]]
  (let [group-field (or group-field :custom1)
        x (parse-endnote-file fname)]
    (let [entries (xml-find [x] [:records :record])]
      (->> entries
           (map (fn [e]
                  (let [title (-> (xml-find [e] [:titles :title :style])
                                  first :content first)
                        journal (-> (xml-find [e] [:titles :secondary-title :style])
                                    first :content first)
                        rdb-name (-> (xml-find [e] [:remote-database-name :style])
                                     first :content first)
                        group (-> (xml-find [e] [group-field :style])
                                  first :content first)]
                    {:title title
                     :journal journal
                     :rdb-name rdb-name
                     :group group})))
           (group-by :group)))))

(defn group-name-to-label-values
  "Creates a label-values map from a label group string (from the XML export)."
  [project-id gname]
  (->>
   (cond
       ;;;;
       ;;;; these are the group labels from the Huili export
       ;;;;
     (= gname "io vaccine/virus")
     {"overall include" true
      "is cancer" true
      "is human" true
      "is clinical trial" true
      "is phase 1" true
      "is immunotherapy" true
      "conference abstract" false
      "combination trial" false
      "single agent trial" false
      "vaccine or virus study" true}
     (= gname "immunotherapy - preclinical")
     {"overall include" false
      "is immunotherapy" true
      "is clinical trial" false}
     (= gname "io combination")
     {"overall include" true
      "is cancer" true
      "is human" true
      "is clinical trial" true
      "is phase 1" true
      "is immunotherapy" true
      "conference abstract" false
      "combination trial" true
      "single agent trial" false
      "vaccine or virus study" false}
     (= gname "immunotherapy - review article")
     {"overall include" false
      "is immunotherapy" true
      "is clinical trial" false}
     (= gname "immunotherapy (other)")
     {"overall include" false
      "is immunotherapy" false}
     (= gname "not cancer")
     {"overall include" false
      "is cancer" false}
     (= gname "io monotherapy")
     {"overall include" true
      "is cancer" true
      "is human" true
      "is clinical trial" true
      "is phase 1" true
      "is immunotherapy" true
      "conference abstract" false
      "combination trial" false
      "single agent trial" true
      "vaccine or virus study" false}
     (= gname "not clinical trial paper")
     {"overall include" false
      "is clinical trial" false}
     (= gname "immunotherapy - not phase 1")
     {"overall include" false
      "is immunotherapy" true
      "is clinical trial" true
      "is phase 1" false}
     (= gname "not immunotherapy")
     {"overall include" false
      "is immunotherapy" false}
     (= gname "immunotherapy - case reports")
     {"overall include" false
      "is immunotherapy" true
      "is clinical trial" false}
       ;;;; above are the group labels from Huili export
       ;;;;
       ;;;; now, group labels from Daphne/Amy export
     (= gname "IMMUNO-MONOTHERAPY")
     {"overall include" true
      "is cancer" true
      "is human" true
      "is clinical trial" true
      "is phase 1" true
      "is immunotherapy" true
      "conference abstract" false
      "combination trial" false
      "single agent trial" true
      "vaccine or virus study" false}
     (= gname "IMMUNO-VACCINE/VIRUS")
     {"overall include" true
      "is cancer" true
      "is human" true
      "is clinical trial" true
      "is phase 1" true
      "is immunotherapy" true
      "conference abstract" false
      "combination trial" false
      "single agent trial" false
      "vaccine or virus study" true}
     (= gname "Not cancer related")
     {"overall include" false
      "is cancer" false}
     (= gname "Not clinical trial paper")
     {"overall include" false
      "is clinical trial" false
      "is phase 1" false}
     (= gname "Not immunotherapy related")
     {"overall include" false
      "is immunotherapy" false}
     (= gname "pediatrics")
     {"overall include" false}
     (= gname "phase III")
     {"overall include" false
      "is phase 1" false}
     (= gname "Preclinical")
     {"overall include" false
      "is human" false
      "is clinical trial" false
      "is phase 1" false}
     (= gname "Transplant")
     {"overall include" false}
     (= gname "Phase I/phase 1/clinical trial")
     {"is phase 1" true
      "is clinical trial" true}
     :else
     nil)
   (map (fn [[name val]]
          [(q/label-id-from-name project-id name) val]))
   (apply concat)
   (apply hash-map)))

(defn store-endnote-labels
  "Records all the labels contained in Endnote XML export file `fname` as
  being set and confirmed by user `user-id`."
  [project-id user-ids fname & [group-field]]
  (let [user-ids (if (integer? user-ids) [user-ids] user-ids)]
    (->>
     (load-endnote-file fname group-field)
     (pmap
      (fn [[gname gentries]]
        (let [label-values (group-name-to-label-values project-id gname)]
          (println (format "label-values for '%s' = %s"
                           gname (pr-str label-values)))
          (assert ((comp not empty?) label-values))
          (->>
           gentries
           (map
            (fn [entry]
              (let [article-id (match-article-id project-id
                                                 (:title entry)
                                                 (:journal entry)
                                                 (:rdb-name entry))]
                (println (format "setting labels for article #%s" article-id))
                (doseq [user-id user-ids]
                  (when article-id
                    (-> (delete-from :article-label)
                        (where [:and
                                [:= :user-id user-id]
                                [:= :article-id article-id]])
                        do-execute)
                    (labels/set-user-article-labels
                     user-id article-id label-values true))))))
           doall))))
     doall))
  true)

