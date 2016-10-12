(ns sysrev.db.endnote
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.xml :as dxml]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [sysrev.db.core :refer
             [do-query do-execute do-transaction]]
            [sysrev.db.users :as users]
            [clojure.string :as str]))

(defn xml-find [roots path]
  (if (empty? path)
    roots
    (xml-find
     (flatten
      (mapv (fn [root]
              (filterv (fn [child]
                         (= (:tag child) (first path)))
                       (:content root)))
            roots))
     (rest path))))

(defn parse-endnote-file [fname]
  (-> fname
      io/file io/reader dxml/parse))

(defn match-article-id
  "Attempt to find an article_id in the database which is the best match
  for the given fields. Requires an exact match on title and journal fields,
  allows for differences in remote_database_name and attempts to select an 
  appropriate match."
  [title journal rdb-name]
  (let [results (-> (select :article_id :remote_database_name)
                    (from :article)
                    (where [:and
                            [:= :primary_title title]
                            [:= :secondary_title journal]])
                    (order-by :article_id)
                    do-query)]
    (if (empty? results)
      (do (println (format "no article match: '%s' '%s' '%s'"
                           title journal rdb-name))
          nil)
      ;; Attempt a few different ways of finding the best match based
      ;; on remote_database_name.
      (or
       ;; exact match
       (->> results
            (filter #(= (:remote_database_name %) rdb-name))
            first
            :article_id)
       ;; case-insensitive match
       (->> results
            (filter #(and (string? (:remote_database_name %))
                          (string? rdb-name)
                          (= (-> % :remote_database_name str/lower-case)
                             (-> rdb-name str/lower-case))))
            first
            :article_id)
       ;; prefer embase
       (->> results
            (filter #(and (string? (:remote_database_name %))
                          (= (-> % :remote_database_name str/lower-case)
                             "embase")))
            first
            :article_id)
       ;; then prefer medline
       (->> results
            (filter #(and (string? (:remote_database_name %))
                          (= (-> % :remote_database_name str/lower-case)
                             "medline")))
            first
            :article_id)
       ;; otherwise use earliest inserted article match
       (-> results first :article_id)))))

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
  [gname]
  (let [cid (fn [cname]
              (-> (select :criteria_id)
                  (from :criteria)
                  (where [:= :name cname])
                  do-query
                  first
                  :criteria_id))]
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
  [user-id fname]
  (->>
   (load-endnote-file fname)
   (mapv
    (fn [[gname gentries]]
      (future
        (let [label-values (group-name-to-label-values gname)]
          (println (format "label-values for '%s' = %s"
                           gname (pr-str label-values)))
          (assert (not (empty? label-values)))
          (when ((comp not empty?) label-values)
            (doseq [entry gentries]
              (let [article-id (match-article-id (:title entry)
                                                 (:journal entry)
                                                 (:rdb-name entry))]
                (println (format "setting labels for article #%s" article-id))
                (when article-id
                  (-> (delete-from :article_criteria)
                      (where [:and
                              [:= :user_id user-id]
                              [:= :article_id article-id]])
                      do-execute)
                  (users/set-user-article-labels
                   user-id article-id label-values true))))))
        true)))
   (mapv deref))
  true)

