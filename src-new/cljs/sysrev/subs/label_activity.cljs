(ns sysrev.subs.label-activity
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]
            [sysrev.shared.util :refer [in?]]))

(def answer-types
  [:single :consistent :conflict :resolved])

(defprotocol Conflictable
  (is-resolved? [this])
  (is-concordance? [this])
  (is-discordance? [this])
  (is-single? [this])
  (resolution [this]))

(defprotocol IdGroupable
  (id-grouped [this]))

(defprotocol AnswerGroupable
  (label-grouped [this]))

(defprotocol Groupable
  (num-groups [this]))

(defprotocol UserGroupable
  (user-grouped [this]))

(defrecord UserGrouping [user-id article-label])

(defrecord ArticleLabel [article-id primary-title user-id answer])

(defrecord LabelGrouping [answer articles]
  Groupable
  (num-groups [this] (count articles)))

(defrecord IdGrouping [article-id article-labels]
  Groupable
  (num-groups [this] (count article-labels))
  AnswerGroupable
  (label-grouped [this]
    (->> article-labels
         (filterv
          (fn [{:keys [answer]}]
            (not (or (nil? answer)
                     (and (coll? answer) (empty? answer))))))
         (group-by :answer)
         (mapv (fn [[k v]] (->LabelGrouping k v)))))
  UserGroupable
  (user-grouped [this]
    (mapv (fn [[k v]] (->UserGrouping k (first v)))
          (group-by :user-id article-labels)))
  Conflictable
  (is-resolved? [this]
    (->> article-labels (mapv :resolve) (filterv identity) first))
  (resolution [this]
    (->> article-labels (filterv :resolve) first))
  (is-discordance? [this]
    (and (not (is-resolved? this))
         (-> (label-grouped this) (keys) (count) (> 1))))
  (is-single? [this]
    (= 1 (num-groups this)))
  (is-concordance? [this]
    (and (not (is-resolved? this))
         (-> (label-grouped this) (count) (= 1) (and (not (is-single? this)))))))

(defrecord ArticleLabels [articles]
  IdGroupable
  (id-grouped [this]
    (mapv (fn [[k v]] (->IdGrouping k v))
          (group-by :article-id articles))))

(defn- answer-status-filter [status]
  (case status
    :conflict is-discordance?
    :resolved is-resolved?
    :consistent is-concordance?
    :single is-single?
    #(do true)))

(defn- answer-value-filter [value]
  (if (nil? value)
    #(do true)
    (fn [article-group]
      (let [answers (->> article-group
                         :article-labels
                         (mapv :answer)
                         (mapv #(if (sequential? %) % [%]))
                         (apply concat)
                         distinct)]
        (in? answers value)))))

;;;
;;;
;;;

(reg-sub
 :label-activity/raw
 (fn [[_ label-id]]
   [(subscribe [:project/raw])])
 (fn [[project] [_ label-id]]
   (get-in project [:label-activity label-id])))

(reg-sub
 :label-activity/articles
 (fn [[_ label-id filters]]
   [(subscribe [:label-activity/raw label-id])])
 (fn [[entries] [_ label-id {:keys [answer-status answer-value]
                             :as filters}]]
   (->> entries
        (mapv map->ArticleLabel)
        (->ArticleLabels)
        (id-grouped)
        (filter (answer-status-filter answer-status))
        (filter (answer-value-filter answer-value)))))
