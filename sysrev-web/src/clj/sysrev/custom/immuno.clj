;;
;; This code is mostly specific to the Immunotherapy project.
;;

(ns sysrev.custom.immuno
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer
             [do-query do-execute do-transaction]]
            [sysrev.db.users :as users]
            [sysrev.db.labels :as labels]
            [sysrev.util :refer [xml-find]]
            [clojure.string :as str]))

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
  "Parse the Endnote XML file into a map containing the fields we're
  interested in."
  [fname]
  (let [x (parse-endnote-file fname)]
    (let [entries (xml-find [x] [:records :record])]
      (->> entries
           (map (fn [e]
                  (let [title (-> (xml-find [e] [:titles :title :style])
                                  first :content first)
                        journal (-> (xml-find [e] [:titles :secondary-title :style])
                                    first :content first)
                        rdb-name (-> (xml-find [e] [:remote-database-name :style])
                                     first :content first)
                        group (-> (xml-find [e] [:custom1 :style])
                                  first :content first)]
                    {:title title
                     :journal journal
                     :rdb-name rdb-name
                     :group group})))
           (group-by :group)))))

(defn group-name-to-label-values
  "Creates a label-values map from a label group string (from the XML export)."
  [project-id gname]
  (let [cid (fn [cname]
              (-> (select :criteria-id)
                  (from :criteria)
                  (where [:and
                          [:= :project-id project-id]
                          [:= :name cname]])
                  do-query
                  first
                  :criteria-id))]
    (->>
     (cond
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
       :else
       nil)
     (map (fn [[name val]]
            [(cid name) val]))
     (apply concat)
     (apply hash-map))))

(defn store-endnote-labels
  "Records all the labels contained in Endnote XML export file `fname` as
  being set and confirmed by user `user-id`."
  [project-id user-id fname]
  (->>
   (load-endnote-file fname)
   (pmap
    (fn [[gname gentries]]
      (let [label-values (group-name-to-label-values project-id gname)]
        (println (format "label-values for '%s' = %s"
                         gname (pr-str label-values)))
        (assert (not (empty? label-values)))
        (when ((comp not empty?) label-values)
          (doseq [entry gentries]
            (let [article-id (match-article-id project-id
                                               (:title entry)
                                               (:journal entry)
                                               (:rdb-name entry))]
              (println (format "setting labels for article #%s" article-id))
              (when article-id
                (-> (delete-from :article-criteria)
                    (where [:and
                            [:= :user-id user-id]
                            [:= :article-id article-id]])
                    do-execute)
                (labels/set-user-article-labels
                 user-id article-id label-values true)))))
        true)))
   doall)
  true)

