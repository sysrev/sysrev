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

(defn active-label-values
  "Get the active label values for `article-id` by taking the values
  pulled from the server and overriding with state values set by the user.

  Uses `article-label-values` because [:data :article-labels] contains
  the latest server values while editing."
  [article-id labels-path]
  (when-let [user-id (s/current-user-id)]
    (merge (or (article-label-values article-id user-id) {})
           (or (apply st labels-path) {}))))

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
                 (let [answer (get label-values (:label-id label))]
                   (or (nil? answer)
                       (and (coll? answer)
                            (empty? answer))))))))

(defn find-inconsistent-answers [label-values]
  (let [overall-inclusion (get label-values (project :overall-label-id))]
    (when (true? overall-inclusion)
      (->> (project-labels-ordered)
           (filter (fn [{:keys [label-id] :as label}]
                     (let [answer (get label-values label-id)
                           inclusion (label-answer-inclusion label-id answer)]
                       (false? inclusion))))))))

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

(defn editing-article-labels? []
  (boolean
   (and (s/current-user-id)
        (or (and (= (s/current-page) :classify)
                 (data :classify-article-id))
            (and (= (s/current-page) :article)
                 (= (user-article-status (st :page :article :id))
                    :unconfirmed))))))

(defn active-labels-path []
  (case (s/current-page)
    :classify [:page :classify :label-values]
    :article [:page :article :label-values]
    nil))

(defn active-article-id-editing []
  (when (editing-article-labels?)
    (case (s/current-page)
      :classify (data :classify-article-id)
      :article (st :page :article :id)
      nil)))


