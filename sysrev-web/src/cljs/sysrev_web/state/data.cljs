(ns sysrev-web.state.data
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.util :refer [map-values]]
            [sysrev-web.state.core :refer [current-user-id]]))

(defn data
  ([ks]
   (data ks nil))
  ([ks not-found]
   (let [ks (if (keyword? ks) [ks] ks)]
     (get-in @state (concat [:data] ks) not-found))))

(defn set-user-info [user-id umap]
  (fn [s]
    (assoc-in s [:data :users user-id] umap)))

(defn user-info [user-id]
  (data [:users user-id]))

(defn set-criteria [criteria]
  (fn [s]
    (-> s
        (assoc-in [:data :criteria] criteria)
        (assoc-in [:data :overall-cid]
                  (->> criteria
                       (filter (fn [[cid {:keys [name]}]]
                                 (= name "overall include")))
                       first first)))))

(defn set-all-labels [labels]
  (fn [s]
    (assoc-in s [:data :labels] labels)))

(defn merge-articles [articles]
  (fn [s]
    (update-in s [:data :articles] #(merge % articles))))

(defn set-ranking-page [page-num ranked-ids]
  (fn [s]
    (assoc-in s [:data :ranking :pages page-num] ranked-ids)))

(defn set-project-info [pmap]
  (fn [s]
    (assoc-in s [:data :sysrev] pmap)))

(defn set-article-labels [article-id lmap]
  (fn [s]
    (assoc-in s [:data :article-labels article-id] lmap)))

(defn article-label-values
  "Looks up label values for `article-id` from all users on the project.
  If multiple users have stored a value for a label, only the first value
  found for each label will be returned."
  [article-id]
  (let [cids (-> @state :data :criteria keys)
        labels (get-in @state [:data :labels article-id])
        known-value
        (fn [cid]
          (->> labels
               (filter #(and (= (:criteria_id %) cid)
                             (not (nil? (:answer %)))))
               first))
        any-value
        (fn [cid]
          (->> labels
               (filter #(= (:criteria_id %) cid))
               first))]
    (->> cids
         (mapv
          (fn [cid]
            (if-let [label (known-value cid)]
              [cid label]
              (if-let [label (any-value cid)]
                [cid label]
                nil))))
         (apply concat)
         (apply hash-map))))

(defn user-label-values [article-id user-id]
  (let [lmap (get-in @state [:data :users user-id :labels])
        amap (or (get-in lmap [:confirmed article-id])
                 (get-in lmap [:unconfirmed article-id]))]
    (->> amap
         (group-by :criteria_id)
         (map-values first))))

(defn active-label-values
  "Get the active label values for `article-id` by taking the values
  pulled from the server and overriding with state values set by the user."
  [article-id labels-path]
  (when-let [user-id (current-user-id)]
    (merge (data [:article-labels article-id user-id] {})
           (get-in @state labels-path {}))))
