(ns sysrev.label.core
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer [select from where merge-where insert-into values]]
            [honeysql-postgres.helpers :as psqlh]
            [medley.core :as medley]
            [sysrev.db.core :as db :refer [do-query with-project-cache]]
            [sysrev.db.queries :as q]
            [sysrev.encryption :as enc]
            [sysrev.project.core :as project]
            [sysrev.shared.spec.labels :as sl]
            [sysrev.util :as util :refer [in? map-values index-by or-default sanitize-uuids
                                          sum uuid-from-string]]))

;; for clj-kondo
(declare user-article-confirmed?)

(def valid-label-categories   ["inclusion criteria" "extra"])
(def valid-label-value-types  ["boolean" "categorical" "string" "group" "annotation"])

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
                       :root-label-id-local root-label-id-local
                       :owner-project-id project-id}
                (boolean? consensus)        (assoc :consensus consensus)
                (= name "overall include")  (assoc :consensus true))
              :returning :*)))

(defn- add-label-entry-boolean
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
                    (dissoc :label-id :project-id :owner-project-id :global-label-id))))))

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

(defn sanitize-labels [m]
  (->> (sanitize-uuids m)
       ;; convert all integer keywords back to strings
       (walk/postwalk #(cond-> %
                         (and (keyword? %) (util/parse-integer (name %)))
                         (name)))))

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

(defn-spec user-article-confirmed? boolean?
  [user-id int?, article-id int?]
  (pos? (q/find-count :article-label {:article-id article-id :user-id user-id}
                      :where [:!= :confirm-time nil])))

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
           (->> (q/find [:article-resolve :ar] {:a.project-id project-id, :a.enabled true}
                        :ar.*, :join [[:article :a] :ar.article-id])
                (group-by :article-id)
                (map-values (fn [xs] (first (->> xs (sort-by #(-> % :resolve-time tc/to-epoch) >)))))
                (map-values (fn [x] (some-> x (update :label-ids #(mapv util/to-uuid %)))))))]
      (apply merge (for [article-id (keys all-labels)]
                     {article-id
                      {:updated-time (get-in all-labels [article-id :updated-time])
                       :labels (->> (get-in all-labels [article-id :labels])
                                    (group-by :label-id)
                                    (map-values (fn [xs] (map #(dissoc % :label-id :confirm-epoch) xs))))
                       :resolve (get all-resolve article-id)}})))))

(defn query-progress-over-time [project-id n-days]
  (with-project-cache project-id [:public-labels :progress n-days]
    (let [labeled (for [{:keys [labels updated-time]}
                        (vals (query-public-article-labels project-id))]
                    {:updated-time updated-time
                     :users (count (->> (vals labels) flatten (map :user-id) distinct))})
          now (tc/to-epoch (t/now))
          day-seconds (* 60 60 24)
          tformat (tf/formatters :year-month-day)]
      (vec (for [day-idx (range 0 n-days)]
             (let [day-epoch (- now (* day-idx day-seconds))
                   before-day? #(< (:updated-time %) day-epoch)]
               {:day (tf/unparse tformat (tc/from-long (* 1000 day-epoch)))
                :labeled (->> labeled (filter before-day?) (map :users) sum)}))))))

(defn- article-current-resolve-entry
  "Returns most recently created article_resolve entry for article. By
  default, the value is taken from query-public-article-labels
  (cached function); use keyword argument direct? to query from
  database directly."
  [project-id article-id & {:keys [direct?]}]
  (if direct?
    (some-> (first (q/find :article-resolve {:article-id article-id} :*
                           :order-by [:resolve-time :desc], :limit 1))
            (update :label-ids #(mapv util/to-uuid %)))
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
  [project-id article-id & {:keys [articles]}]
  (let [alabels (-> (or articles (query-public-article-labels project-id))
                    (get article-id))
        label-defs (->> (project/project-labels project-id)
                        ;; filter to either group label (consensus is
                        ;; filtered by individual label) or consensus
                        (util/filter-values #(or (= (:value-type %) "group")
                                                 (:consensus %))))
        label-answers (for [{:keys [label-id value-type]} (vals label-defs)]
                        {:label-id label-id
                         :value-type value-type
                         :answers (->> (get-in alabels [:labels label-id])
                                       (mapv :answer))})]
    (->> label-answers
         (filter (comp not concordant?))
         (map :label-id))))

;;; this should check to see if there is still a conflict
;;; e.g. handle the case where labels are resolved but further
;;; changes are made to the article, creating new conflicts
(defn article-resolved-status
  "If article consensus status is resolved, returns a map of the
  corresponding article_resolve entry; otherwise returns nil.

  Consensus status is resolved if an article_resolve entry exists,
  unless the label_ids field of the entry does not contain all active
  project consensus labels."
  [project-id article-id]
  (when-let [resolve (article-current-resolve-entry project-id article-id)]
    (let [group-label-ids (->> (project/project-labels project-id)
                               (util/filter-values #(= (:value-type %) "group"))
                               keys set)
          conflict-ids (->> (article-conflict-label-ids project-id article-id)
;;; filtering out group labels, for now the method below gets hairy
;;; for multiple group labels we're just going to assume if a group
;;; label has a resolve entry, that is the article label final value
                            (remove #(contains? group-label-ids %))
                            set)
          resolve-ids (set (:label-ids resolve))]
      (when (empty? (set/difference conflict-ids resolve-ids))
        (dissoc resolve :article-id)))))

(defn article-consensus-status
  "Returns keyword representing consensus status of confirmed answers
  for article, or nil if article has no confirmed answers."
  [project-id article-id & {:keys [articles overall-id]}]
  (let [articles (or articles (query-public-article-labels project-id))
        overall-id (or overall-id (project/project-overall-label-id project-id))
        overall-labels (get-in articles [article-id :labels overall-id])
        conflict-ids (article-conflict-label-ids project-id article-id :articles articles)
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
    (let [articles (query-public-article-labels project-id)
          overall-id (project/project-overall-label-id project-id)]
      (->> articles
           ((if (nil? db/*conn*) pmap map) ;; use pmap unless running inside db transaction
            (fn [[article-id entry]]
              (let [labels (get-in entry [:labels overall-id])
                    group-status (article-consensus-status project-id article-id
                                                           :articles articles :overall-id overall-id)
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
                  (map (fn [status] {status (count (->> (vals entries) (filter #(= % status))))}))
                  (apply merge))))))

;;; TODO: this might get slow for large projects. Should create a table
;;; that tracks this. Could also use the article-status table
(defn count-reviewed-articles [project-id]
  (q/find-count [:article :a] {:a.project-id project-id :a.enabled true}
                :where (q/exists [:article-label :al]
                                 {:al.article-id :a.article-id})))

(defn project-article-statuses [project-id]
  (let [articles (query-public-article-labels project-id)
        overall-id (project/project-overall-label-id project-id)]
    (when overall-id
      (vec (for [[article-id article-labels] articles]
             (let [labels (get-in article-labels [:labels overall-id])
                   group-status (article-consensus-status project-id article-id
                                                          :articles articles :overall-id overall-id)
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
    (let [users (-> (->>
                      (q/find [:project-member :pm] {:pm.project-id project-id}
                              [:u.*
                               :pm.membership-id
                               [:pm.permissions :project-permissions]
                               [:g.name :gengroup-name]
                               [:g.gengroup-id :gengroup-id]]
                              :join [[[:web-user :u] :pm.user-id]]
                              :left-join [[[:project-member-gengroup-member :pmgm] [:and
                                                                                    [:= :pmgm.project-id :pm.project-id]
                                                                                    [:= :pmgm.membership-id :pm.membership-id]]]
                                          [[:gengroup :g] :pmgm.gengroup-id]])

                      (group-by :user-id)
                      (map (fn [[_ items]]
                             (let [gengroups (->> items
                                                  (filter :gengroup-id)
                                                  (map #(select-keys % [:gengroup-name :gengroup-id]))
                                                  vec)]
                               (-> (first items)
                                   (dissoc :gengroup-name)
                                   (dissoc :gengroup-id)
                                   (assoc :gengroups gengroups)))))
                      (index-by :user-id)))
          inclusions (project-user-inclusions project-id)]
      (map-values (fn [{:keys [user-id membership-id project-permissions gengroups email]}]
                    {:email email
                     :membership-id membership-id
                     :permissions project-permissions
                     :gengroups gengroups
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
                                          :owner-project-id project-id
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

(defn get-share-code [label-id]
  (enc/encrypt {:type "label-share-code"
                :label-id (str label-id)}))

(defn import-label [share-code target-project-id]
  (db/with-clear-project-cache target-project-id
    (let [share-data (enc/decrypt share-code)
          label-id (uuid-from-string (:label-id share-data))
          label (get-label label-id)
          cloned-label (-> label
                           (dissoc :label-id :label-id-local)
                           (assoc :project-id target-project-id
                                  :owner-project-id (:project-id label)
                                  :global-label-id label-id))]
      (q/create :label cloned-label
                :returning :*))))

;(import-label (get-share-code "f6f44bb4-2393-4c36-b935-d0bc2865b975") 34472)
