(ns sysrev-web.data
  (:require [sysrev-web.base :refer [state server-data]]
            [sysrev-web.util :refer [map-values]]))

(defn get-label-values
  "Looks up label values for `article-id` from all users on the project.
  If multiple users have stored a value for a label, only the first value
  found for each label will be returned."
  [article-id]
  (let [cids (-> @server-data :criteria keys)
        labels (get-in @server-data [:labels article-id])
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

(defn get-user-label-values [article-id user-id]
  (let [lmap (get-in @server-data [:users user-id :labels])
        amap (or (get-in lmap [:confirmed article-id])
                 (get-in lmap [:unconfirmed article-id]))]
    (->> amap
         (group-by :criteria_id)
         (map-values first))))
