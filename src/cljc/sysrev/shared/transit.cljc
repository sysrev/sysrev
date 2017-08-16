(ns sysrev.shared.transit
  (:require [sysrev.shared.util :refer [map-values]]))

;;;
;;; /api/public-labels
;;;

(defn- encode-public-labels-labels [content]
  (->> content
       (map-values
        (fn [entries]
          (->> entries
               (mapv (fn [{:keys [user-id answer inclusion resolve]}]
                       [user-id answer inclusion resolve])))))))

(defn- decode-public-labels-labels [content]
  (->> content
       (map-values
        (fn [entries]
          (->> entries
               (mapv (fn [[user-id answer inclusion resolve]]
                       {:user-id user-id
                        :answer answer
                        :inclusion inclusion
                        :resolve resolve})))))))

(defn encode-public-labels [content]
  (->> (vec content)
       (sort-by (comp #(or (:updated-time %) 0)
                      second)
                >)
       (mapv (fn [[article-id {:keys [title updated-time labels]}]]
               [article-id title updated-time (encode-public-labels-labels labels)]))))

(defn decode-public-labels
  [content & {:keys [remapify?] :or {remapify? false}}]
  (->> content
       (mapv (fn [[article-id title updated-time labels]]
               [article-id {:title title
                            :updated-time updated-time
                            :labels (decode-public-labels-labels labels)}]))
       (#(if remapify?
           (->> % (apply concat) (apply hash-map))
           (->> % (mapv (fn [[article-id entry]]
                          (assoc entry :article-id article-id))))))))

;;;
;;; /api/member-articles
;;;

(defn- encode-member-articles-labels [content]
  (->> content
       (map-values
        (fn [entries]
          (->> entries
               (mapv (fn [{:keys [user-id answer inclusion resolve]}]
                       [user-id answer inclusion resolve])))))))

(defn- decode-member-articles-labels [content]
  (->> content
       (map-values
        (fn [entries]
          (->> entries
               (mapv (fn [[user-id answer inclusion resolve]]
                       {:user-id user-id
                        :answer answer
                        :inclusion inclusion
                        :resolve resolve})))))))

(defn encode-member-articles [content]
  (->> (vec content)
       (sort-by (comp #(or (:updated-time %) 0)
                      second)
                >)
       (mapv (fn [[article-id {:keys [title updated-time confirmed notes labels]}]]
               [article-id title updated-time confirmed notes
                (encode-member-articles-labels labels)]))))

(defn decode-member-articles
  [content & {:keys [remapify?] :or {remapify? false}}]
  (->> content
       (mapv (fn [[article-id title updated-time confirmed notes labels]]
               [article-id (cond->
                               {:title title
                                :updated-time updated-time
                                :confirmed confirmed
                                :labels (decode-member-articles-labels labels)}
                             notes (assoc :notes notes))]))
       (#(if remapify?
           (->> % (apply concat) (apply hash-map))
           (->> % (mapv (fn [[article-id entry]]
                          (assoc entry :article-id article-id))))))))
