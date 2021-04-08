(ns sysrev.annotations
  (:require [clj-time.coerce :as tc]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.datasource.api :as ds-api]
            [sysrev.project.core :as project]
            [honeysql.helpers :as sqlh]
            [sysrev.label.core :as label]
            [sysrev.label.answer :as answer]
            [clojure.tools.logging :as log]
            [sysrev.util :as util :refer [map-values]]))

(defn create-annotation! [selection annotation context article-id]
  (q/create :annotation {:selection selection
                         :annotation annotation
                         :context (db/to-jsonb context)
                         :article-id article-id}
            :returning :annotation-id))

(defn associate-ann-user [annotation-id user-id]
  (q/create :ann-user {:annotation-id annotation-id :user-id user-id}))

(defn associate-ann-s3 [annotation-id s3-id]
  (q/create :ann-s3store {:annotation-id annotation-id :s3-id s3-id}))

(defn ann-user-id [annotation-id]
  (q/find-one :ann-user {:annotation-id annotation-id} :user-id))

(defn ann-project-id [annotation-id]
  (q/find-one [:annotation :ann] {:ann.annotation-id annotation-id}
              :a.project-id, :left-join [[:article :a] :ann.article-id]))

(defn- get-annotations-by [match-by]
  (vec (q/find [:annotation :ann] match-by
               [:ann.* :ann-u.user-id :ann-s3.s3-id [:ann-sc.definition :semantic-class]]
               :left-join [[[:ann-user :ann-u]        :ann.annotation-id]
                           [[:ann-s3store :ann-s3]    :ann.annotation-id]
                           [[:semantic-class :ann-sc] :ann.semantic-class-id]])))

;; extremely useful graph:
;; https://stackoverflow.com/questions/20602826/sql-server-need-join-but-where-not-equal-to
(defn user-defined-article-annotations [article-id]
  (get-annotations-by {:ann.article-id article-id :ann-s3.s3-id nil}))

(defn user-defined-article-pdf-annotations [article-id s3-id]
  (get-annotations-by {:ann.article-id article-id :ann-s3.s3-id s3-id}))

(defn delete-annotation! [annotation-id]
  (db/with-clear-project-cache (ann-project-id annotation-id)
    (q/delete :annotation {:annotation-id annotation-id})))

(defn update-annotation!
  "Update `annotation` and `semantic-class-id` fields for `annotation-id`,
  creating a new `semantic-class` entry if needed. Empty string values
  for `semantic-class` will be converted to nil."
  [annotation-id annotation & [semantic-class]]
  (db/with-clear-project-cache (ann-project-id annotation-id)
    (let [semantic-class (not-empty semantic-class)
          ;; look up `semantic-class-id` value or create entry if needed
          {:keys [semantic-class-id]}
          (or (first (q/find :semantic-class {:definition (db/to-jsonb semantic-class)}))
              (q/create :semantic-class {:definition (db/to-jsonb semantic-class)}
                        :returning :*))]
      (assert (integer? semantic-class-id))
      ;; update `annotation` and `semantic-class-id` fields
      (q/modify :annotation {:annotation-id annotation-id}
                {:annotation annotation :semantic-class-id semantic-class-id}))))

(defn find-annotation [match-by fields & {:keys [] :as opts}]
  (vec (util/apply-keyargs
        q/find [:annotation :ann] match-by fields
        (merge opts {:left-join (concat [[[:semantic-class :ann-sc] :ann.semantic-class-id]
                                         [[:ann-user :ann-u]        :ann.annotation-id]
                                         [[:ann-s3store :ann-s3]    :ann.annotation-id]
                                         [[:s3store :s3]            :ann-s3.s3-id]
                                         [[:article :a]             :ann.article-id]
                                         [[:article-data :ad]       :a.article-data-id]]
                                        (:left-join opts))}))))

(defn project-annotations
  "Retrieve all annotations for project-id"
  [project-id]
  (let [entries (find-annotation {:a.project-id project-id}
                                 [:ann.selection :ann.annotation :ann.context :ann-sc.definition
                                  :ann-u.user-id :a.article-id :s3.key :s3.filename
                                  :ad.external-id :ad.datasource-name])
        articles (-> (distinct (map :article-id entries))
                     (ds-api/get-articles-content))]
    (mapv (fn [{:keys [article-id] :as entry}]
            (merge entry (select-keys (get articles article-id)
                                      [:primary-title :secondary-title :abstract])))
          entries)))

(defn find-project-ids-with-annotations
  "Retrieve all project IDs that have annotations"
  []
  (let [res (-> (sqlh/select :article.project-id)
                (sqlh/modifiers :distinct)
                (sqlh/from [:annotation :ann])
                (sqlh/join :article [:= :ann.article-id :article.article-id])
                db/do-query)]
    (map :project-id res)))

(defn migrate-old-annotations []
  (db/with-transaction
    (let [project-ids (find-project-ids-with-annotations)
          general-annotation-name "Annotations"]
      (doseq [project-id project-ids]
        (let [project-labels (project/project-labels project-id true)
              has-been-migrated? (contains? (->> project-labels vals (map :name) set)
                                            general-annotation-name)]
          (when-not has-been-migrated?
            (let [annotations (find-annotation {:a.project-id project-id}
                                               [:ann.annotation-id :ann.selection :ann.annotation
                                                :ann.context :ann-sc.definition
                                                :ann-u.user-id :a.article-id :s3.key :s3.filename
                                                :ad.external-id :ad.datasource-name])
                  article-ids (distinct (map :article-id annotations))
                  all-values (->> (map :definition annotations)
                                  (filter some?)
                                  (distinct))
                  general-annotation-definition
                  {:name general-annotation-name
                   :question "Define general annotations for this article"
                   :short-label "Annotations"
                   :category "extra"
                   :enabled true
                   :project-ordering (count project-labels)
                   :required false
                   :consensus false
                   :value-type "annotation"
                   :definition {:all-values all-values}
                   :root-label-id-local nil}
                  {:keys [label-id]} (label/add-label-entry
                                      project-id general-annotation-definition)]
              (doseq [article-id article-ids]
                (let [article-annotations (filter #(= (:article-id %) article-id) annotations)
                      article-annotations-per-user (group-by :user-id article-annotations)]
                  (doseq [[user-id user-annotations] article-annotations-per-user]
                    (let [label-values
                          (->> user-annotations
                               (map (fn [annotation]
                                      [(str "ann-" (:annotation-id annotation))
                                       {:annotation-id (str "ann-" (:annotation-id annotation))
                                        :selection (:selection annotation)
                                        :context (:context annotation)
                                        :semantic-class (:definition annotation)
                                        :value (:annotation annotation)}]))
                               (into {}))]
                      (answer/set-user-article-labels
                       user-id article-id {label-id label-values}
                       :imported? false
                       :confirm? true
                       :change? true
                       :resolve? false))))))))))))

(defn project-annotations-basic
  "Retrieve all annotations for project-id"
  [project-id]
  (find-annotation {:a.project-id project-id}
                   [:ann.selection :ann.annotation :ann.context :ann-sc.definition :ann-u.user-id
                    :a.article-id :s3.filename [:s3.key :file-key]]))

(defn project-annotation-status [project-id & {:keys [user-id]}]
  (->> (find-annotation (cond-> {:a.project-id project-id}
                          user-id (merge {:ann-u.user-id user-id}))
                        [:ann.created :a.article-id :ann-sc.definition])
       (group-by :definition)
       (map-values (fn [anns]
                     {:last-used (->> (map (comp tc/to-epoch :created) anns)
                                      (apply max 0) tc/from-epoch tc/to-sql-time)
                      :count (count anns)}))))

(defn project-article-annotations [project-id & {:keys [include-disabled?]}]
  (db/with-project-cache project-id [:annotations :articles include-disabled?]
    (->> (find-annotation (cond-> {:a.project-id project-id}
                            (not include-disabled?) (merge {:a.enabled true}))
                          [:ann.article-id :ann.created :ann-sc.definition :ann-u.user-id])
         (group-by :article-id)
         (map-values (fn [anns]
                       {:updated-time (->> (map (comp tc/to-epoch :created) anns)
                                           (apply max 0) tc/from-epoch tc/to-sql-time)
                        :users (distinct (map :user-id anns))
                        :count (count anns)
                        :classes (distinct (->> (map :definition anns) (remove nil?)))})))))

(defn text-context-article-field-match
  "Determine which field of an article text-context matches in article-id."
  [text-context article-id]
  (when-let [article (ds-api/get-article-content article-id)]
    (let [fields (select-keys article [:primary-title :secondary-title :abstract])]
      (or (->> (keys fields)
               (filter #(= text-context (get fields %)))
               first)
          text-context))))
