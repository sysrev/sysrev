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

(defn set-user-info [user-id umap]
  (fn [s]
    (assoc-in s [:data :users user-id] umap)))

(defn user-info [user-id]
  (data [:users user-id]))

(defn set-member-labels [user-id lmap]
  (fn [s]
    (assoc-in s [:data :project (active-project-id) :labels user-id] lmap)))

(defn member-labels [user-id]
  (project [:labels user-id]))

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
    (let [old-labels (data [:project (:project-id pmap) :labels])
          new-labels (:labels pmap)
          pmap
          (assoc pmap
                 :overall-cid
                 (->> (:criteria pmap)
                      (filter (fn [[cid {:keys [name]}]]
                                (= name "overall include")))
                      first first)
                 :labels (merge old-labels new-labels))]
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
  (let [cids (-> (project :criteria) keys)
        labels (get-in @state [:data :article-labels article-id])
        known-value
        (fn [cid]
          (->> labels
               (filter #(and (= (:criteria-id %) cid)
                             (not (nil? (:answer %)))))
               first))
        any-value
        (fn [cid]
          (->> labels
               (filter #(= (:criteria-id %) cid))
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
  (let [lmap (project [:labels user-id])
        amap (or (get-in lmap [:confirmed article-id])
                 (get-in lmap [:unconfirmed article-id]))]
    (->> amap
         (group-by :criteria-id)
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
