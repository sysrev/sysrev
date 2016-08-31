(ns sysrev-web.data
  (:require [sysrev-web.base :refer [state server-data]]))

(defn get-label-values
  "Looks up label values for `article-id` from all users on the project.
  If multiple users have stored a value for a label, only the first value
  found for each label will be returned.

  Alternatively, if `user-id` is specified then only values from that user
  will be included."
  [article-id & [user-id]]
  (let [cids (-> @server-data :criteria keys)
        labels (get-in @server-data [:labels article-id])
        user-matches #(or (nil? user-id)
                          (= (:user_id %) user-id))
        known-value
        (fn [cid]
          (->> labels
               (filter user-matches)
               (filter #(and (= (:criteria_id %) cid)
                             (not (nil? (:answer %)))))
               first))
        any-value
        (fn [cid]
          (->> labels
               (filter user-matches)
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

(defn user-article-ids-sorted
  "Gets the list of `article-id`s that `user-id` has labeled,
  sorted by function `sort-key` applied to the article object.

  Optional argument `ascending?` (default false) specifies the
  sort direction (default descending)."
  [user-id sort-key & [ascending?]]
  (let [articles-map (get-in @server-data [:users user-id :articles])]
    (->>
     (concat (:includes articles-map)
             (:excludes articles-map))
     (map #(get-in @server-data [:articles %]))
     (remove nil?)
     (sort-by sort-key (if ascending? > <))
     (map :article_id))))

(defn current-user-data []
  (let [user-id (:id (:user @state))
        user-pred (fn [u] (= user-id (-> u :user :id)))
        all-users (:users @server-data)]
    (first (filter user-pred all-users))))
