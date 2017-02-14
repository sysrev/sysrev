(ns sysrev.state.labels
  (:require [sysrev.base :refer [st work-state]]
            [sysrev.state.core :as s :refer [data]]
            [sysrev.state.project :refer [project]]
            [sysrev.util :refer [in?]]
            [sysrev.shared.util :refer [map-values]])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(defn set-member-labels [user-id lmap]
  (fn [s]
    (assoc-in
     s [:data :project (s/current-project-id) :member-labels user-id] lmap)))

(defn filter-labels
  [labels {:keys [category value-type]}]
  (cond->> labels
    category (filter #(= (:category %) category))
    value-type (filter #(= (:value-type %) value-type))))

(defn set-article-labels [article-id lmap]
  (fn [s]
    (assoc-in s [:data :article-labels article-id] lmap)))

(defn article-label-values [article-id & [user-id]]
  (let [path (cond-> [:data :article-labels article-id]
               user-id (conj user-id))]
    (apply st path)))

(defn user-label-values [article-id user-id]
  (let [lmap (project :member-labels user-id)
        amap (or (get-in lmap [:confirmed article-id])
                 (get-in lmap [:unconfirmed article-id]))
        result
        (->> amap
             (group-by :label-id)
             (map-values first)
             (map-values :answer))]
    (if-not (empty? result)
      result
      (article-label-values article-id user-id))))

(defn user-article-status [article-id]
  (let [user-id (s/current-user-id)
        confirmed
        (and user-id (project :member-labels user-id
                              :confirmed article-id))
        unconfirmed
        (and user-id (project :member-labels user-id
                              :unconfirmed article-id))]
    (cond (nil? user-id) :logged-out
          confirmed :confirmed
          unconfirmed :unconfirmed
          :else :none)))

(defn project-labels-ordered
  "Return label definition entries ordered first by category/type and then
  by project-ordering value."
  []
  (let [group-idx
        (fn [{:keys [required category value-type]}]
          (let [inclusion? (= category "inclusion criteria")
                extra? (= category "extra")
                boolean? (= value-type "boolean")
                categorical? (= value-type "categorical")]
            (cond
              (and required inclusion? boolean?) -10
              (and required inclusion?) -9
              (and required boolean?) -8
              required -7
              (and inclusion? boolean?) 0
              (and inclusion? categorical?) 1
              inclusion? 2
              (and extra? boolean?) 3
              (and extra? categorical?) 4
              extra? 5
              :else 6)))]
    (->> (vals (project :labels))
         (sort-by #(vector
                    (group-idx %) (:project-ordering %))
                  <))))

(defn editing-article-labels? []
  (boolean
   (and (s/current-user-id)
        (or (and (= (s/current-page) :classify)
                 (data :classify-article-id))
            (and (= (s/current-page) :article)
                 (= (user-article-status (st :page :article :id))
                    :unconfirmed))))))

(defn active-editor-article-id []
  (when (editing-article-labels?)
    (case (s/current-page)
      :classify (data :classify-article-id)
      :article (st :page :article :id)
      nil)))

(defn active-labels-path
  "Returns the path in the global state map where label input values for the
  article currently being edited are stored."
  []
  (when-let [article-id (active-editor-article-id)]
    (case (s/current-page)
      :classify [:page :classify :label-values article-id]
      :article [:page :article :label-values article-id]
      nil)))

(defn active-label-values
  "Get the active label values for `article-id` by taking the values
  pulled from the server and overriding with state values set by the user.

  Uses `article-label-values` because [:data :article-labels] contains
  the latest server values while editing.

  `article-id` defaults to the active editor article if not given.

  `label-id` can be passed to return only the value of a single label."
  [& [article-id label-id]]
  (when-let [user-id (s/current-user-id)]
    (when-let [article-id (or article-id (active-editor-article-id))]
      (cond->
          (let [labels-path (active-labels-path)]
            (merge (or (article-label-values article-id user-id) {})
                   (or (and labels-path (apply st labels-path)) {})))
        label-id (get label-id)))))

(defn set-label-value
  "Sets the value of a label in the active editor."
  [label-id label-value]
  (using-work-state
   (let [labels-path (active-labels-path)]
     (assert ((comp not empty?) labels-path))
     (swap! work-state assoc-in
            (concat labels-path [label-id])
            label-value))))

(defn update-label-value
  "Alters the active value of a label by applying function `f` to the
  current value."
  [label-id f]
  (using-work-state
   (let [labels-path (active-labels-path)]
     (assert ((comp not empty?) labels-path))
     (let [curval (active-label-values nil label-id)]
       (swap! work-state assoc-in
              (concat labels-path [label-id])
              (f curval))))))

(defn string-label-valid? [label-id val]
  (let [{:keys [definition value-type]} (project :labels label-id)]
    (when (= value-type "string")
      (boolean
       (let [{:keys [regex max-length]} definition]
         (and
          (string? val)
          (<= (count val) max-length)
          (or (empty? regex)
              (some #(re-matches (re-pattern %) val) regex))))))))

(defn label-answer-valid? [label-id answer]
  (let [{:keys [definition value-type]} (project :labels label-id)]
    (boolean
     (case value-type
       "boolean"
       (in? [true false nil] answer)
       "categorical"
       (cond (nil? answer)
             true
             (sequential? answer)
             (let [allowed (-> definition :all-values)]
               (every? (in? allowed) answer))
             :else false)
       ;; TODO check that answer value matches label regex
       "string"
       (let [{:keys [regex max-length]} definition]
         (and
          (sequential? answer)
          (every? #(string-label-valid? label-id %) answer)))
       true))))

(defn label-answer-inclusion [label-id answer]
  (let [{:keys [definition value-type]} (project :labels label-id)
        ivals (:inclusion-values definition)]
    (case value-type
      "boolean"
      (cond
        (empty? ivals) nil
        (nil? answer) nil
        :else (boolean (in? ivals answer)))
      "categorical"
      (cond
        (empty? ivals) nil
        (nil? answer) nil
        (empty? answer) nil
        :else (boolean (some (in? ivals) answer)))
      nil)))

(defn required-answers-missing [label-values]
  (->> (project-labels-ordered)
       (filter :required)
       (filter (fn [label]
                 (let [v (get label-values (:label-id label))]
                   (or (nil? v)
                       (and (coll? v) (empty? v))))))))

(defn find-inconsistent-answers [label-values]
  (let [overall-inclusion (get label-values (project :overall-label-id))]
    (when (true? overall-inclusion)
      (->> (project-labels-ordered)
           (filter (fn [{:keys [label-id] :as label}]
                     (let [answer (get label-values label-id)
                           inclusion (label-answer-inclusion label-id answer)]
                       (false? inclusion))))))))

(defn filter-valid-label-values [label-values]
  (->> label-values
       (filterv
        (fn [[label-id answer]]
          (label-answer-valid? label-id answer)))
       (apply concat)
       (apply hash-map)))
