(ns sysrev.db.export
  (:require [clojure.string :as str]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.db.core :refer [do-query with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.label.core :as label]
            [sysrev.db.project :as project]
            [sysrev.shared.util :refer [map-values in?]]
            [clojure.set :as set]))

;; TODO: replace this with better format (importable, comprehensive, tested)
(defn export-project [project-id]
  (let [articles
        (-> (q/select-project-articles
             project-id [:article-id :primary-title :secondary-title :authors :abstract
                         :year :urls :keywords :remote-database-name :work-type])
            (->> do-query
                 (group-by :article-id)
                 (map-values first)
                 (map-values #(-> (assoc % :locations {} :user-labels {} :user-notes {})
                                  (set/rename-keys {:primary-title :title
                                                    :secondary-title :journal})))))
        all-article-ids (apply hash-set (keys articles))
        alocations
        (-> (q/select-project-articles
             project-id [:aloc.article-id :aloc.source :aloc.external-id])
            (q/join-article-locations)
            (->> do-query
                 (filter #(contains? all-article-ids (:article-id %)))
                 (group-by :article-id)
                 (map-values (fn [xs] (->> xs (map #(dissoc % :article-id)))))
                 (map-values #(merge {} {:locations %}))))
        anotes
        (-> (q/select-project-articles
             project-id [:an.article-id :an.user-id :an.content :an.updated-time])
            (q/with-article-note "default")
            (->> do-query
                 (filter #(contains? all-article-ids (:article-id %)))
                 (group-by :article-id)
                 (map-values (fn [xs] (->> xs (map #(dissoc % :article-id)))))
                 (map-values #(merge {} {:user-notes %}))))
        ldefs-vec
        (-> (q/select-label-where
             project-id nil [:l.label-id :l.label-id-local :l.name :l.question
                             :l.short-label :l.value-type :l.required :l.category
                             :l.definition])
            do-query vec)
        l-uuid-to-int
        (->> ldefs-vec
             (group-by :label-id)
             (map-values first)
             (map-values :label-id-local))
        ldefs-map
        (->> ldefs-vec
             (group-by :label-id-local)
             (map-values first)
             (map-values #(let [int-id (:label-id-local %)]
                            (-> (dissoc % :label-id :label-id-local)
                                (assoc :label-id int-id)))))
        alabels
        (-> (q/select-project-articles
             project-id [:al.article-id :al.label-id :al.user-id :al.answer
                         :al.inclusion :al.resolve :al.updated-time])
            (q/join-article-labels)
            (q/filter-valid-article-label true)
            (->> do-query
                 (filter #(contains? all-article-ids (:article-id %)))
                 (group-by :article-id)
                 (map-values
                  (fn [xs] (->> xs (map #(-> (dissoc % :article-id)
                                             (update :label-id l-uuid-to-int))))))
                 (map-values #(merge {} {:user-labels %}))))
        users
        (-> (q/select-project-members
             project-id [:u.user-id :u.email :u.admin :u.permissions])
            (->> do-query
                 (remove #(or (:admin %) (in? (:permissions %) "admin")))
                 (map #(select-keys % [:user-id :email]))
                 (sort-by :user-id <)))]
    {:articles (->> (merge-with merge articles alocations alabels anotes)
                    vals (sort-by :article-id <))
     :project-labels (->> ldefs-map vals (sort-by :label-id <))
     :users users
     :version "1.0.1"}))

(defn export-user-answers
  "Returns CSV-printable list of raw user article answers. The first row
  contains column names; each following row contains answers for one
  value of (user,article)."
  [project-id]
  (with-transaction
    (let [all-labels (-> (q/select-label-where
                          project-id true
                          [:label-id :short-label])
                         (order-by :label-id-local)
                         do-query)
          all-articles (-> (q/select-project-articles
                            project-id [:a.article-id
                                        :a.primary-title
                                        :a.secondary-title
                                        :a.authors])
                           (merge-where
                            [:exists
                             (-> (q/select-project-article-labels
                                  project-id true [:al.*])
                                 (merge-where [:= :l.enabled true]))])
                           (->> do-query
                                (group-by :article-id)
                                (map-values first)))
          user-answers (-> (q/select-project-articles
                            project-id [:al.article-id
                                        :al.label-id
                                        :al.user-id
                                        :al.answer
                                        :al.resolve
                                        :al.updated-time
                                        :u.email])
                           (q/join-article-labels)
                           (q/filter-valid-article-label true)
                           (q/join-article-label-defs)
                           (merge-where [:= :l.enabled true])
                           (q/join-users :al.user-id)
                           (->> do-query
                                (group-by #(vector (:article-id %) (:user-id %)))
                                vec
                                (sort-by first)
                                (mapv second)))
          user-notes (-> (q/select-project-articles
                          project-id [:anote.article-id
                                      :anote.user-id
                                      :anote.content])
                         (merge-join [:article-note :anote]
                                     [:= :anote.article-id :a.article-id])
                         (->> do-query
                              (group-by #(vector (:article-id %) (:user-id %)))
                              (map-values first)))]
      (concat
       [(concat ["Article ID" "User Name" "Resolve?"]
                (map :short-label all-labels)
                ["User Note" "Title" "Journal" "Authors"])]
       (->> user-answers
            (mapv
             (fn [user-article]
               (let [{:keys [article-id user-id email]}
                     (first user-article)

                     resolved? (-> (label/article-resolved-status
                                    project-id article-id)
                                   :user-id
                                   (= user-id))

                     user-name (first (str/split email #"@"))
                     user-note (:content (get user-notes [article-id user-id]))

                     {:keys [primary-title secondary-title authors]}
                     (get all-articles article-id)

                     label-answers
                     (->> all-labels
                          (map :label-id)
                          (map (fn [label-id]
                                 (->> user-article
                                      (filter #(= (:label-id %) label-id))
                                      first :answer))))

                     all-authors (->> authors (map str) (str/join "; "))]
                 (->> (concat
                       [article-id user-name (if (true? resolved?) true nil)]
                       label-answers
                       [user-note primary-title secondary-title all-authors])
                      (mapv #(cond (or (nil? %) (and (coll? %) (empty? %))) ""
                                   (sequential? %) (->> % (map str) (str/join ", "))
                                   :else (str %))))))))))))

(defn all-nonconflict-article-ids [project-id]
  (->> (keys (label/query-public-article-labels project-id))
       (filterv (fn [article-id]
                 (->> (label/article-consensus-status project-id article-id)
                      (in? [:single :consistent :resolved]))))))

(defn export-group-answers
  "Returns CSV-printable list of combined group answers for
  articles. The first row contains column names; each following row
  contains answers for one article. article-ids optionally specifies a
  subset of articles within project to include; by default, all
  enabled articles will be included."
  [project-id & {:keys [article-ids]}]
  (with-transaction
    (let [all-labels (-> (q/select-label-where
                          project-id true
                          [:label-id :short-label])
                         (order-by :label-id-local)
                         do-query)
          all-articles (-> (q/select-project-articles
                            project-id [:a.article-id
                                        :a.primary-title
                                        :a.secondary-title
                                        :a.authors])
                           (merge-where
                            [:exists
                             (-> (q/select-project-article-labels
                                  project-id true [:al.*])
                                 (merge-where [:= :l.enabled true]))])
                           (->> do-query
                                (group-by :article-id)
                                (map-values first)))
          anotes (-> (q/select-project-articles
                      project-id [:anote.article-id
                                  :anote.user-id
                                  :anote.content])
                     (merge-join [:article-note :anote]
                                 [:= :anote.article-id :a.article-id])
                     (->> do-query (group-by :article-id)))
          aanswers (-> (q/select-project-articles
                        project-id [:al.article-id
                                    :al.label-id
                                    :al.user-id
                                    :al.answer
                                    :al.updated-time
                                    :u.email])
                       (q/join-article-labels)
                       (q/filter-valid-article-label true)
                       (q/join-article-label-defs)
                       (merge-where [:= :l.enabled true])
                       (q/join-users :al.user-id)
                       (->> do-query (group-by :article-id)))]
      (concat
       [(concat ["Article ID" "Article URL" "Status" "User Count" "Users"]
                (map :short-label all-labels)
                ["User Notes" "Title" "Journal" "Authors"])]
       (->> (if (empty? article-ids)
              aanswers
              (-> aanswers (select-keys article-ids)))
            keys
            sort
            (mapv
             (fn [article-id]
               (let [{:keys [primary-title secondary-title authors]}
                     (get all-articles article-id)
                     user-names (->> (get aanswers article-id)
                                     (map :email)
                                     distinct
                                     (map #(-> % (str/split #"@") first)))
                     user-count (count user-names)
                     user-notes (->> (get anotes article-id)
                                     (map :content))
                     consensus (label/article-consensus-status
                                project-id article-id)
                     resolved-labels (label/article-resolved-labels
                                      project-id article-id)
                     get-label-values
                     (fn [label-id]
                       (as-> (if (not-empty resolved-labels)
                               (get resolved-labels label-id)
                               (->> (get aanswers article-id)
                                    (filter #(= (:label-id %) label-id))
                                    (map :answer)
                                    (map #(if (sequential? %) % [%]))
                                    (apply concat)
                                    distinct
                                    sort)) lvals
                         (if (sequential? lvals) lvals [lvals])))
                     all-authors (->> authors (map str) (str/join "; "))
                     all-notes (->> user-notes (map pr-str) (str/join "; "))
                     article-url (str "https://sysrev.com/p/" project-id
                                      "/article/" article-id)]
                 (->> (concat
                       [article-id
                        article-url
                        (name (or consensus :none))
                        user-count
                        user-names]
                       (->> all-labels
                            (map :label-id)
                            (map get-label-values))
                       [all-notes primary-title secondary-title all-authors])
                      (mapv #(cond (or (nil? %) (and (coll? %) (empty? %))) ""
                                   (sequential? %) (->> % (map str) (str/join ", "))
                                   :else (str %))))))))))))
