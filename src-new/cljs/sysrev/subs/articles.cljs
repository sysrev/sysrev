(ns sysrev.subs.articles
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]
            [sysrev.subs.auth :as auth]))

(reg-sub
 :articles/all
 (fn [db]
   (get-in db [:data :articles])))

(defn have-article? [db article-id]
  (contains? (get-in db [:data :articles]) article-id))
(reg-sub
 :article/raw
 :<- [:articles/all]
 (fn [articles [_ article-id]]
   (get articles article-id)))

(reg-sub
 :article/review-status
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:review-status article)))

(reg-sub
 :article/title
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:primary-title article)))

(reg-sub
 :article/journal-name
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:secondary-title article)))

(reg-sub
 :article/authors
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:authors article)))

(reg-sub
 :article/abstract
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:abstract article)))

(reg-sub
 :article/title-render
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:title-render article)))

(reg-sub
 :article/journal-render
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:journal-render article)))

(reg-sub
 :article/abstract-render
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:abstract-render article)))

(reg-sub
 ::urls
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:urls article)))

(reg-sub
 :article/locations
 (fn [[_ article-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article]]
   (:locations article)))

(defn- article-location-urls [locations]
  (let [sources [:pubmed :doi :pii :nct]]
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
                :nct (str "https://clinicaltrials.gov/ct2/show/" external-id)
                nil)))))))
     (apply concat)
     (filter identity))))

(reg-sub
 :article/urls
 (fn [[_ article-id]]
   [(subscribe [::urls article-id])
    (subscribe [:article/locations article-id])])
 (fn [[urls locations]]
   (concat urls (article-location-urls locations))))

(defn article-labels [db article-id & [user-id label-id]]
  (when-let [labels (get-in db [:data :articles article-id :labels])]
    (cond-> labels
      user-id                (get user-id)
      (and user-id label-id) (get label-id))))

(reg-sub
 :article/labels
 (fn [[_ article-id user-id label-id]]
   [(subscribe [:article/raw article-id])])
 (fn [[article] [_ article-id user-id label-id]]
   (cond-> (:labels article)
     user-id                 (get user-id)
     (and user-id label-id)  (get label-id))))

(defn- article-user-status-impl [user-id ulmap]
  (cond (nil? user-id)
        :logged-out

        (empty? ulmap)
        :none

        (some :confirmed (vals ulmap))
        :confirmed

        :else
        :unconfirmed))

(defn article-user-status [db article-id & [user-id]]
  (let [user-id (or user-id (auth/current-user-id db))
        ulmap (article-labels db article-id user-id)]
    (article-user-status-impl user-id ulmap)))

(reg-sub
 :article/user-status
 (fn [[_ article-id user-id]]
   [(subscribe [:self/user-id])
    (subscribe [:article/labels article-id])])
 (fn [[self-id alabels] [_ _ user-id]]
   (let [user-id (or user-id self-id)
         ulmap (get alabels user-id)]
     (article-user-status-impl user-id ulmap))))

(reg-sub
 :article/user-resolved?
 (fn [[_ article-id user-id]]
   (assert user-id ":article/user-resolved? - user-id must be passed")
   [(subscribe [:article/labels article-id user-id])])
 (fn [[ulmap]]
   (->> (vals ulmap) (map :resolve) first)))
