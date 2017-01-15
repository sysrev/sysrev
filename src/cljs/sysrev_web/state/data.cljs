(ns sysrev-web.state.data
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.util :refer [map-values in?]]
            [sysrev-web.state.core :refer [current-user-id active-project-id]]))

(defn data
  ([ks]
   (data ks nil))
  ([ks not-found]
   (let [ks (if (keyword? ks) [ks] ks)]
     (get-in @state (concat [:data] ks) not-found))))

(defn project
  ([ks not-found]
   (let [ks (if (keyword? ks) [ks] ks)]
     (when-let [project-id (active-project-id)]
       (data (concat [:project (active-project-id)] ks) not-found))))
  ([ks]
   (project ks nil))
  ([]
   (project [] nil)))

(defn filter-labels
  [labels {:keys [category value-type]}]
  (cond->> labels
    category (filter #(= (:category %) category))
    value-type (filter #(= (:value-type %) value-type))))

(defn project-label [label-id]
  (get (project [:labels]) label-id))

(defn project-keywords []
  (project [:keywords]))

(defn project-labels-ordered
  "Return label definition entries ordered first by category/type and then
  by project-ordering value."
  []
  (let [group-idx
        (fn [{:keys [required category value-type]}]
          (let [inclusion? (= category "inclusion criteria")
                extra? (= category "extra")
                note? (= category "note")
                boolean? (= value-type "boolean")
                categorical? (= value-type "categorical")
                text-box? (= value-type "text-box")]
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
              (not note?) 6
              :else 7)))]
    (->> (project [:labels])
         vals
         (sort-by #(vector
                    (group-idx %) (:project-ordering %))
                  <))))

(defn set-user-info [user-id umap]
  (fn [s]
    (assoc-in s [:data :users user-id] umap)))

(defn user-info [user-id]
  (data [:users user-id]))

(defn set-member-labels [user-id lmap]
  (fn [s]
    (assoc-in s [:data :project (active-project-id) :member-labels user-id] lmap)))

(defn member-labels [user-id]
  (project [:member-labels user-id]))

(defn merge-article [article]
  (fn [s]
    (update-in s [:data :articles]
               #(assoc % (:article-id article) article))))

(defn merge-articles [articles]
  (fn [s]
    (update-in s [:data :articles] #(merge-with merge % articles))))

(defn merge-documents [documents]
  (fn [s]
    (update-in s [:data :documents] #(merge % documents))))

(defn merge-users [users]
  (fn [s]
    (update-in s [:data :users] #(merge % users))))

(defn set-project-info [pmap]
  (fn [s]
    (let [;; need to merge in :member-labels field because it's loaded from a
          ;; separate request (/api/member-labels)
          old-member-labels (data [:project (:project-id pmap) :member-labels])
          new-member-labels (:member-labels pmap)
          pmap
          (assoc pmap
                 :overall-label-id
                 (->> (:labels pmap)
                      (filter (fn [[label-id {:keys [name]}]]
                                (= name "overall include")))
                      first first)
                 :member-labels
                 (merge old-member-labels new-member-labels))]
      (assert (:project-id pmap))
      (assoc-in s [:data :project (:project-id pmap)] pmap))))

(defn set-all-projects [pmap]
  (fn [s]
    (assoc-in s [:data :all-projects] pmap)))

(defn set-article-labels [article-id lmap]
  (fn [s]
    (assoc-in s [:data :article-labels article-id] lmap)))

(defn get-article-labels [article-id & [user-id]]
  (if user-id
    (get-in @state [:data :article-labels article-id user-id])
    (get-in @state [:data :article-labels article-id])))

(defn article-label-values
  "Looks up label values for `article-id` from all users on the project.
  If multiple users have stored a value for a label, only the first value
  found for each label will be returned."
  [article-id]
  (let [label-ids (-> (project :labels) keys)
        labels (get-in @state [:data :article-labels article-id])
        known-value
        (fn [label-id]
          (->> labels
               (filter #(and (= (:label-id %) label-id)
                             (not (nil? (:answer %)))))
               first))
        any-value
        (fn [label-id]
          (->> labels
               (filter #(= (:label-id %) label-id))
               first))]
    (->> label-ids
         (mapv
          (fn [label-id]
            (if-let [label-val (known-value label-id)]
              [label-id label-val]
              (if-let [label-val (any-value label-id)]
                [label-id label-val]
                nil))))
         (apply concat)
         (apply hash-map))))

(defn user-label-values [article-id user-id]
  (let [lmap (project [:member-labels user-id])
        amap (or (get-in lmap [:confirmed article-id])
                 (get-in lmap [:unconfirmed article-id]))]
    (->> amap
         (group-by :label-id)
         (map-values first)
         (map-values :answer))))

(defn active-label-values
  "Get the active label values for `article-id` by taking the values
  pulled from the server and overriding with state values set by the user."
  [article-id labels-path]
  (when-let [user-id (current-user-id)]
    (merge (data [:article-labels article-id user-id] {})
           (get-in @state labels-path {}))))

(defn article-documents [article-id]
  (when-let [article (data [:articles article-id])]
    (let [doc-ids (:document-ids article)]
      (->> doc-ids
           (map
            (fn [doc-id]
              (let [fnames (data [:documents (js/parseInt doc-id)])]
                (->> fnames
                     (map (fn [fname]
                            {:document-id doc-id
                             :file-name fname}))))))
           (apply concat)
           vec))))

(defn article-document-url [doc-id file-name]
  (str "/files/PDF/" doc-id "/" file-name))

(defn project-user-info [user-id]
  (project [:users user-id]))

(defn real-user? [user-id]
  (in? (:permissions (user-info user-id)) "user"))

(defn admin-user? [user-id]
  (in? (:permissions (user-info user-id)) "admin"))

(defn article-location-urls [locations]
  (let [sources [:pubmed :doi :pii]]
    (->>
     sources
     (map
      (fn [source]
        (let [entries (get locations source)]
          (->>
           entries
           (map
            (fn [{:keys [external-id]}]
              (case source
                :pubmed (str "https://www.ncbi.nlm.nih.gov/pubmed/?term=" external-id)
                :doi (str "https://dx.doi.org/" external-id)
                :pmc (str "https://www.ncbi.nlm.nih.gov/pmc/articles/" external-id "/")
                nil)))))))
     (apply concat)
     (filter identity))))

(defn reset-code-info [reset-code]
  (data [:reset-code reset-code]))

(defn set-reset-code-info [reset-code rmap]
  (fn [s]
    (assoc-in s [:data :reset-code reset-code] rmap)))

(defn label-answer-inclusion [label-id answer]
  (let [{:keys [definition value-type]} (project [:labels label-id])
        ivals (-> definition :inclusion-values)]
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
