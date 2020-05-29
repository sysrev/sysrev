(ns sysrev.label.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [select from join where merge-where
                                               order-by limit insert-into values]]
            [honeysql-postgres.helpers :as psqlh]
            [medley.core :as medley]
            [sysrev.db.core :as db :refer [do-query with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.project.core :as project]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.util :as util :refer [in? map-values index-by or-default]]))

(def valid-label-categories   ["inclusion criteria" "extra"])
(def valid-label-value-types  ["boolean" "categorical" "string" "group"])

(defn-spec get-label (s/nilable ::sl/label)
  [label-id ::sl/label-id, & args (s/? any?)]
  (apply q/find-one :label {:label-id label-id} args))

(defn next-project-ordering [project-id & [root-label-id-local]]
  (or-default 0 (some-> (-> (select [:%max.project-ordering :max])
                            (from :label)
                            (where [:and
                                    [:= :project-id project-id]
                                    [:= :root-label-id-local root-label-id-local]])
                            do-query
                            first
                            :max)
                        inc)))

(defn add-label-entry
  "Creates an entry for a label definition.
  Ordinarily this will be directly called only by one of the type-specific
  label creation functions."
  [project-id {:keys [name question short-label category enabled project-ordering
                      required consensus value-type definition root-label-id-local]
               :or {enabled true
                    root-label-id-local nil}}]
  (assert (in? valid-label-categories category))
  (assert (in? valid-label-value-types value-type))
  (db/with-clear-project-cache project-id
    (q/create :label
              (cond-> {:project-id project-id
                       :project-ordering (when enabled
                                           (if root-label-id-local
                                             project-ordering
                                             (next-project-ordering project-id root-label-id-local)))
                       :value-type value-type
                       :name name
                       :question question
                       :short-label short-label
                       :required required
                       :category category
                       :definition (db/to-jsonb definition)
                       :enabled enabled
                       :root-label-id-local root-label-id-local}
                (boolean? consensus)        (assoc :consensus consensus)
                (= name "overall include")  (assoc :consensus true))))
  true)

(defn add-label-entry-boolean
  "Creates an entry for a boolean label definition.

  `name` `question` `short-label` are strings describing the label.

  `inclusion-value` may be `true` or `false` to set that value as required
  for overall inclusion, or may be `nil` for no inclusion requirement.

  `required` is `true` or `false` to determine if this label must be set for an  article

  `custom-category` is optional, unless specified the label category will be
  determined from the value of `inclusion-value`."
  [project-id
   {:keys [name question short-label inclusion-value required consensus custom-category]
    :as entry-values}]
  (add-label-entry
   project-id (merge (->> [:name :question :short-label :required :consensus]
                          (select-keys entry-values))
                     {:value-type "boolean"
                      :category (or custom-category (if (nil? inclusion-value)
                                                      "extra" "inclusion criteria"))
                      :definition (when-not (nil? inclusion-value)
                                    {:inclusion-values [inclusion-value]})})))

;; this is used in tests only
(defn add-label-entry-categorical
  "Creates an entry for a categorical label definition.

  `name` `question` `short-label` are strings describing the label.

  `all-values` are the values from which to choose from

  `inclusion-values` should be a sequence of the values that are acceptable
  for inclusion. If `inclusion-values` is empty, answers for this label
  are treated as having no relationship to inclusion. If `inclusion-values` is
  not empty, answers are treated as implying inclusion if any of the answer
  values is present in `inclusion-values`; non-empty answers for which none of
  the values is present in `inclusion-values` are treated as implying
  exclusion.

  `custom-category` is optional, unless specified the label category will be
  determined from the value of `inclusion-value`."
  [project-id
   {:keys [name question short-label all-values inclusion-values required consensus
           multi? custom-category] :as entry-values}]
  (assert (sequential? all-values))
  (assert (sequential? inclusion-values))
  (add-label-entry
   project-id (merge (->> [:name :question :short-label :required :consensus]
                          (select-keys entry-values))
                     {:value-type "categorical"
                      :category (or custom-category (if (empty? inclusion-values)
                                                      "extra" "inclusion criteria"))
                      :definition {:all-values all-values
                                   :inclusion-values inclusion-values
                                   :multi? (boolean multi?)}})))
;; this is used in tests only
(defn add-label-entry-string
  "Creates an entry for a string label definition. Value is provided by user
  in a text input field.

  `name` `question` `short-label` are strings describing the label.
  `max-length` is a required integer.
  `regex` is an optional vector of strings to require that answers must match
  one of the regex values.
  `entity` is an optional string to identify what the value represents.
  `examples` is an optional list of example strings to indicate to users
  the required format.

  `required` is `true` or `false` to determine if this label must be set for an  article

  `multi?` if true allows multiple string values in answer."
  [project-id
   {:keys [name question short-label required consensus custom-category max-length regex
           entity examples multi?] :as entry-values}]
  (assert (= (type multi?) Boolean))
  (assert (integer? max-length))
  (assert (or (nil? regex) (and (coll? regex) (every? string? regex))))
  (assert (or (nil? examples) (and (coll? examples) (every? string? examples))))
  (assert ((some-fn nil? string?) entity))
  (add-label-entry
   project-id (merge (->> [:name :question :short-label :required :consensus]
                          (select-keys entry-values))
                     {:value-type "string"
                      :category (or custom-category "extra")
                      :definition (cond-> {:multi? multi? :max-length max-length}
                                    regex     (assoc :regex regex)
                                    entity    (assoc :entity entity)
                                    examples  (assoc :examples examples))})))

(defn add-label-overall-include [project-id]
  (add-label-entry-boolean
   project-id {:name "overall include"
               :question "Include this article?"
               :short-label "Include"
               :inclusion-value true
               :required true
               :consensus true}))

(defn alter-label-entry [project-id label-id values-map]
  (db/with-clear-project-cache project-id
    (let [current (->> (q/find-one :label {:label-id label-id})
                       (util/assert-pred map?)
                       (util/assert-pred #(= project-id (:project-id %))))
          root-label-id-local (:root-label-id-local current)
          old-enabled (:enabled current)
          new-enabled (get values-map :enabled old-enabled)
          ;; Ensure project-ordering consistency with enabled value
          ordering (cond (not new-enabled) nil
                         (and new-enabled (not old-enabled)) (next-project-ordering project-id root-label-id-local)
                         :else (get values-map :project-ordering (:project-ordering current)))]
      ;; If changing project-ordering value for this label and the
      ;; value already exists in project, increment values for all
      ;; labels where >= this. This avoids setting duplicate ordering
      ;; values and creating an indeterminate label order in the
      ;; project.
      (when (and new-enabled old-enabled ordering
                 (q/find :label {:project-id project-id
                                 :project-ordering ordering}
                         :label-id, :where [:and
                                            [:!= :label-id label-id]
                                            [:= :root-label-id-local root-label-id-local]]))
        (q/modify :label {:project-id project-id}
                  {:project-ordering (sql/raw "project_ordering + 1")}
                  :where [:and
                          [:!= :project-ordering nil]
                          [:>= :project-ordering ordering]
                          [:= :root-label-id-local root-label-id-local]]))
      (q/modify :label {:label-id label-id}
                (-> (assoc values-map :project-ordering ordering)
                    (dissoc :label-id :project-id))))))

(defn set-project-ordering-sequence
  "Ensure the project ordering sequence is correct for project-id with optional root-label-id-local. When root-label-id-local is nil, orders the top-level labels and ignore sublabels"
  [project-id & [root-label-id-local]]
  (doseq [{:keys [label-id project-ordering i]}
          (->> (q/find :label {:project-id project-id :enabled true
                               :root-label-id-local root-label-id-local}
                       :*, :order-by [:project-ordering :asc])
               (map-indexed (fn [i label] (merge label {:i i}))))]
    (when (not= i project-ordering)
      (q/modify :label {:label-id label-id} {:project-ordering i}))))

(defn ensure-correct-project-ordering-sequence
  "Ensure the entire project, sublabels included, are in the correct sequence"
  [project-id]
  (let [all-labels (q/find :label {:project-id project-id :enabled true}
                           :*)]
    (doseq [x (keys (group-by :root-label-id-local all-labels))]
      (set-project-ordering-sequence project-id x))))

(defn adjust-label-project-ordering-values
  "Adjusts project-ordering values for all labels in project to ensure consistency
  with `enabled` value and ensure that the ordering values for enabled labels form
  a continuous sequence."
  [project-id]
  (db/with-clear-project-cache project-id
    (let [labels (q/find :label {:project-id project-id})
          l-enabled (filter :enabled labels)
          l-disabled (remove :enabled labels)]
      ;; ensure nil project-ordering for disabled labels
      (doseq [{:keys [label-id project-ordering]} l-disabled]
        (when-not (nil? project-ordering)
          (q/modify :label {:label-id label-id} {:project-ordering nil})))
      ;; ensure non-nil project-ordering for enabled labels
      (doseq [{:keys [label-id project-ordering root-label-id-local]} l-enabled]
        (when (nil? project-ordering)
          (q/modify :label {:label-id label-id}
                    {:project-ordering (next-project-ordering project-id root-label-id-local)})))
      ;; set project-ordering sequence to (range n-enabled) using
      ;; existing sort order
      (ensure-correct-project-ordering-sequence project-id))))

(defn sanitize-labels
  [m]
  (->> m
       ;; convert all string representations of uuids into uuids
       (walk/postwalk (fn [x] (try
                                ;; the try short-circuits to just x unless
                                ;; x can be transformed to a uuid
                                (let [uuid (-> x symbol str java.util.UUID/fromString)]
                                  (when (uuid? uuid)
                                    uuid))
                                (catch Exception _
                                  x))))
       ;; convert all integer keywords back into string keywords
       (walk/postwalk (fn [x] (if (and (keyword? x) (integer? (read-string (str (symbol x)))))
                                (str (symbol x))
                                x)))))

;; TODO: move into article entity
(defn article-user-labels-map [article-id]
  (-> (q/select-article-by-id article-id [:al.* :l.enabled])
      (q/join-article-labels)
      (q/join-article-label-defs)
      (->> do-query
           (filter :enabled)
           (group-by :user-id)
           (map-values
            #(->> (index-by :label-id %)
                  (map-values
                   (fn [{:keys [confirm-time updated-time] :as entry}]
                     (merge (select-keys entry [:answer :resolve])
                            {:confirmed (not (nil? confirm-time))
                             :confirm-epoch (if (nil? confirm-time) 0
                                                (max (tc/to-epoch confirm-time)
                                                     (tc/to-epoch updated-time)))})))))
           sanitize-labels)))

(defn user-article-confirmed? [user-id article-id]
  (assert (and (integer? user-id) (integer? article-id)))
  (-> (q/select-article-by-id article-id [:%count.*])
      (q/join-article-labels)
      (q/filter-label-user user-id)
      (merge-where [:!= :al.confirm-time nil])
      do-query first :count pos?))

(defn query-public-article-labels [project-id]
  (with-project-cache project-id [:public-labels :values]
    (let [[all-labels all-resolve]
          (pvalues
           (-> (q/select-project-articles
                project-id [:a.article-id :l.label-id :al.answer :al.inclusion
                            :al.resolve :al.confirm-time :al.user-id])
               (q/join-article-labels)
               (q/join-article-label-defs)
               (merge-where [:= :l.enabled true])
               (q/filter-valid-article-label true)
               (->> do-query
                    (map #(-> (assoc % :confirm-epoch (tc/to-epoch (:confirm-time %)))
                              (dissoc :confirm-time)))
                    (group-by :article-id)
                    (map-values (fn [xs] (map #(dissoc % :article-id) xs)))
                    (map-values (fn [xs] {:labels xs
                                          :updated-time (apply max 0 (map :confirm-epoch xs))}))))
           (-> (select :ar.*)
               (from [:article-resolve :ar])
               (join [:article :a] [:= :a.article-id :ar.article-id])
               (where [:and
                       [:= :a.project-id project-id]
                       [:= :a.enabled true]])
               (->> do-query (group-by :article-id)
                    (map-values (fn [xs] (first (->> xs (sort-by #(-> % :resolve-time tc/to-epoch) >)))))
                    (map-values (fn [x] (some-> x (update :label-ids #(mapv util/to-uuid %))))))))]
      (apply merge (for [article-id (keys all-labels)]
                     {article-id
                      {:updated-time (get-in all-labels [article-id :updated-time])
                       :labels (->> (get-in all-labels [article-id :labels])
                                    (group-by :label-id)
                                    (map-values (fn [xs] (map #(dissoc % :label-id :confirm-epoch) xs))))
                       :resolve (get all-resolve article-id)}})))))

(defn query-progress-over-time [project-id n-days]
  (with-project-cache project-id [:public-labels :progress n-days]
    (let [#_ completed #_ nil
          labeled (for [x (vals (query-public-article-labels project-id))]
                    {:updated-time (:updated-time x)
                     :users (count (->> (vals (:labels x)) (apply concat) (map :user-id) distinct))})
          now (tc/to-epoch (t/now))
          day-seconds (* 60 60 24)
          tformat (tf/formatters :year-month-day)]
      (vec (for [day-idx (range 0 n-days)]
             (let [day-epoch (- now (* day-idx day-seconds))
                   before-day? #(< (:updated-time %) day-epoch)]
               {:day (tf/unparse tformat (tc/from-long (* 1000 day-epoch)))
                #_ :completed #_ (->> completed (filter before-day?) count)
                :labeled (->> labeled (filter before-day?) (map :users) (apply +))}))))))

(defn- article-current-resolve-entry
  "Returns most recently created article_resolve entry for article. By
  default, the value is taken from query-public-article-labels
  (cached function); use keyword argument direct? to query from
  database directly."
  [project-id article-id & {:keys [direct?]}]
  (if direct?
    (-> (select :*)
        (from :article-resolve)
        (where [:= :article-id article-id])
        (order-by [:resolve-time :desc])
        (limit 1)
        (some-> do-query
                first
                (update :label-ids #(mapv util/to-uuid %))))
    (-> (query-public-article-labels project-id)
        (get-in [article-id :resolve]))))

(defn group-concordant?
  [m]
  (let [label-id (-> m :label-id)
        project-id (-> (select :project-id)
                       (from :label)
                       (where [:= :label-id label-id])
                       do-query
                       first
                       :project-id)
        consensus-labels (-> (select :label-id)
                             (from :label)
                             (where [:and [:= :project-id project-id] [:not= :root-label-id-local nil] [:= :consensus true]])
                             do-query
                             (->> (map :label-id))
                             set)
        answers (->> m sanitize-labels :answers (map :labels) (map vals))
        consensus-answers (walk/postwalk #(if (map? %)
                                            (-> (medley/filter-keys (fn [k] (contains? consensus-labels k))
                                                                    %)
                                                vals)
                                            %)
                                         answers)
        consensus-answers-comp (map #(->> (group-by identity %) (medley/map-vals count)) consensus-answers)
        concordant? (apply = consensus-answers-comp)]
    concordant?))

(defn concordant?
  "Given a m with keys [:label-id :answers :value-type], is the label concordant?"
  [m]
  (let [boolean-concordant? (fn [m] (-> (:answers m) set count (#(= % 1))))
        categorical-concordant? (fn [m] (->> (:answers m)
                                             (map set)
                                             (sort-by count)
                                             reverse
                                             (apply set/difference)
                                             empty?))
        ;; leaving this open for future algorithm
        string-concordant? (fn [_] true)]
    (if (> (count (:answers m))
           1)
      (condp = (:value-type m)
        "boolean" (boolean-concordant? m)
        "categorical" (categorical-concordant? m)
        "string" (string-concordant? m)
        "group" (group-concordant? m))
      ;; a label must have more than one answer to be discordant
      true)))

(defn article-conflict-label-ids
  "Returns list of consensus labels in project for which article has
  conflicting answers."
  [project-id article-id]
  (let [label-defs (->> (-> (select :label-id :value-type :consensus)
                            (from :label)
                            (where [:and
                                    [:= :project_id project-id]
                                    [:= :root-label-id-local nil]])
                            do-query)
                        ;; filter to either group labels (consensus is filtered by individual label)
                        ;; or consensus labels
                        (filter #(or (and (not= (:value-type %) "group") (:consensus %)) (= (:value-type %) "group"))))
        label-ids (map :label-id label-defs)
        label-answers (-> (select :label-id :answer :user-id)
                          (from :article-label)
                          (where [:= :article_id article-id])
                          do-query)
        label-def-map (sysrev.util/index-by :label-id label-defs)
        get-label-answers (fn [label-id]
                            (->> label-answers
                                 (filter #(= (:label-id %) label-id))
                                 (map :answer)
                                 (into [])))
        label-answers (map #(hash-map
                             :label-id %
                             :answers (get-label-answers %))
                           label-ids)
        label-def-answers (map #(assoc %
                                       :value-type
                                       (get-in label-def-map [(:label-id %) :value-type])) label-answers)
        concordant-result (->> label-def-answers
                               (filter (comp not concordant?))
                               (map :label-id))]
    concordant-result))

;; this should check to see if there is still a conflict
;; e.g. handle the case where labels are resolved but further
;; changes are made to the article, creating new conflicts
(defn article-resolved-status
  "If article consensus status is resolved, returns a map of the
  corresponding article_resolve entry; otherwise returns nil.

  Consensus status is resolved if an article_resolve entry exists,
  unless the label_ids field of the entry does not contain all active
  project consensus labels."
  [project-id article-id]
  (when-let [resolve (article-current-resolve-entry project-id article-id)]
    (let [conflict-ids (article-conflict-label-ids project-id article-id)]
      (when (empty? (set/difference (set conflict-ids) (set (:label-ids resolve))))
        (dissoc resolve :article-id)))))

(defn article-consensus-status
  "Returns keyword representing consensus status of confirmed answers
  for article, or nil if article has no confirmed answers."
  [project-id article-id]
  (let [overall-id (project/project-overall-label-id project-id)
        overall-labels (-> (query-public-article-labels project-id)
                           (get-in [article-id :labels overall-id]))
        conflict-ids (article-conflict-label-ids project-id article-id)
        resolve (article-resolved-status project-id article-id)]
    (cond (empty? overall-labels)       nil
          (= 1 (count overall-labels))  :single
          resolve                       :resolved
          (not-empty conflict-ids)      :conflict
          :else                         :consistent)))

(defn article-resolved-labels
  "If article consensus status is resolved, returns map of {label-id
  answer} for answers provided by resolving user; otherwise returns
  nil."
  [project-id article-id]
  (when-let [resolve (article-resolved-status project-id article-id)]
    (when (user-article-confirmed? (:user-id resolve) article-id)
      (->> (-> (article-user-labels-map article-id)
               (get (:user-id resolve)))
           (map-values :answer)))))

(defn project-included-articles [project-id]
  (let [articles (query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)]
    (when overall-id
      (->> (vec articles)
           (filter
            (fn [[article-id article]]
              (let [labels (get-in article [:labels overall-id])
                    group-status (article-consensus-status project-id article-id)
                    inclusion-status
                    (case group-status
                      :conflict nil
                      :resolved (-> (article-resolved-labels project-id article-id)
                                    (get overall-id))
                      (->> labels (map :inclusion) first))]
                (and (in? [:consistent :resolved] group-status)
                     (true? inclusion-status)))))
           (apply concat)
           (apply hash-map)))))

(defn project-user-inclusions [project-id]
  (with-project-cache project-id [:label-values :confirmed :user-inclusions]
    (let [overall-id (project/project-overall-label-id project-id)
          include? (comp true? :answer)
          exclude? (comp false? :answer)]
      (-> (q/select-project-articles project-id [:al.article-id :al.user-id :al.answer])
          (q/join-article-labels)
          (merge-where [:= :al.label-id overall-id])
          (q/filter-valid-article-label true)
          (->> do-query
               (group-by :user-id)
               (map-values (fn [xs] {:includes (->> xs (filter include?) (mapv :article-id))
                                     :excludes (->> xs (filter exclude?) (mapv :article-id))})))))))

(defn project-article-status-entries [project-id]
  (with-project-cache project-id [:public-labels :status-entries]
    (let [overall-id (project/project-overall-label-id project-id)]
      (->> (query-public-article-labels project-id)
           ((if (nil? db/*conn*) pmap map) ;; use pmap unless running inside db transaction
            (fn [[article-id entry]]
              (let [labels (get-in entry [:labels overall-id])
                    group-status (article-consensus-status project-id article-id)
                    inclusion (case group-status
                                :conflict nil
                                :resolved (-> (article-resolved-labels project-id article-id)
                                              (get overall-id))
                                (->> labels (map :inclusion) first))]
                {article-id [group-status inclusion]})))
           (apply merge)))))

(defn project-article-status-counts [project-id]
  (with-project-cache project-id [:public-labels :status-counts]
    (let [entries (project-article-status-entries project-id)
          articles (query-public-article-labels project-id)]
      (merge {:reviewed (count articles)}
             (->> (distinct (vals entries))
                  (map (fn [status] {status (->> (vals entries) (filter (partial = status)) count)}))
                  (apply merge))))))

(defn project-article-statuses [project-id]
  (let [articles (query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)]
    (when overall-id
      (vec (for [[article-id article-labels] articles]
             (let [labels (get-in article-labels [:labels overall-id])
                   group-status (article-consensus-status project-id article-id)
                   #_ inclusion-status #_ (case group-status
                                            :conflict nil
                                            :resolved (-> (article-resolved-labels project-id article-id)
                                                          (get overall-id))
                                            (->> labels (map :inclusion) first))]
               {:group-status group-status
                :article-id article-id
                :answer (:answer (first labels))}))))))

(defn project-members-info [project-id]
  (with-project-cache project-id [:members-info]
    (let [users (-> (q/select-project-members
                     project-id [:u.* [:m.permissions :project-permissions]])
                    (->> do-query (index-by :user-id)))
          inclusions (project-user-inclusions project-id)]
      (map-values (fn [{:keys [user-id project-permissions]}]
                    {:permissions project-permissions
                     :articles (get inclusions user-id)})
                  users))))

(defn sync-group-label
  "Given a group label, m, sync it with its core label. It does not have to exist on the server"
  [project-id m]
  (db/with-clear-project-cache project-id
    (db/with-transaction
      (let [{:keys [enabled value-type name short-label required category label-id definition]} m
            label-existed? (not (string? label-id)) ; remember: new labels are strings
            current-label (if label-existed?
                            ;; label exists
                            (-> (select :*)
                                (from :label)
                                (where [:= :label-id label-id])
                                do-query
                                first)
                            ;; create the label
                            (-> (insert-into :label)
                                (values [{:project-id project-id
                                          :project-ordering (when enabled (next-project-ordering project-id))
                                          :value-type value-type
                                          :name name
                                          :short-label short-label
                                          :required required
                                          :category category
                                          :enabled enabled
                                          :question "N/A"
                                          :definition definition}])
                                (psqlh/returning :*)
                                do-query
                                first))
            root-label-id-local (:label-id-local current-label)
            client-labels (-> m :labels vals set)
            server-labels (-> (select :*)
                              (from :label)
                              (where [:= :root-label-id-local root-label-id-local])
                              do-query
                              set)
            ;; new labels are given a randomly generated string id on
            ;; the client, so labels that are non-existent on the server
            ;; will have string as opposed to UUID label-ids
            new-client-labels (set (filter (comp string? :label-id) client-labels))
            current-client-labels (set (filter (comp uuid? :label-id) client-labels))
            modified-client-labels (set/difference current-client-labels server-labels)]
        ;; handle the sub labels
        (doseq [label new-client-labels]
          (add-label-entry project-id (assoc label :root-label-id-local root-label-id-local)))
        (doseq [{:keys [label-id] :as label} modified-client-labels]
          (alter-label-entry project-id label-id (assoc label :root-label-id-local root-label-id-local)))
        ;; need to also alter the root label if it already exists
        (when label-existed?
          (->> (alter-label-entry project-id label-id (-> m
                                                          (dissoc :labels)
                                                          (assoc :enabled
                                                                 ;; this label is disabled if all sublabels are disabled
                                                                 (not (every? false? (map :enabled client-labels))))))))
        ;; adjust the label ordering
        (adjust-label-project-ordering-values project-id)
        {:valid? true
         :labels (project/project-labels project-id true)}))))

(defn sync-labels
  "Given a project-id and map containing labels, sync the labels with project "
  [project-id m]
  (db/with-transaction
    (let [client-labels (set (vals m))
          server-labels (-> (project/project-labels project-id true)
                            vals
                            set)
          ;; new labels are given a randomly generated string id on
          ;; the client, so labels that are non-existent on the server
          ;; will have string as opposed to UUID label-ids
          new-client-labels (set (filter (comp string? :label-id) client-labels))
          current-client-labels (set (filter (comp uuid? :label-id) client-labels))
          modified-client-labels (set/difference current-client-labels server-labels)]
      ;; creation/modification of labels should be done
      ;; on labels that have been validated.
      ;;
      ;; labels are never deleted, the enabled flag is set to 'empty'
      ;; instead
      ;;
      ;; If there are issues with a label being incorrectly
      ;; modified, add a validator for that case so that
      ;; the error can easily be reported in the client
      (doseq [label new-client-labels]
        (if (= (:value-type label) "group")
          (sync-group-label project-id label)
          (add-label-entry project-id label)))
      (doseq [{:keys [label-id] :as label} modified-client-labels]
        (if (= (:value-type label) "group")
          (sync-group-label project-id label)
          (alter-label-entry project-id label-id label)))
      (adjust-label-project-ordering-values project-id)
      {:valid? true
       :labels (project/project-labels project-id true)})))
