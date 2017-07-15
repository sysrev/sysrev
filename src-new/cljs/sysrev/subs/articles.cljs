(ns sysrev.subs.articles
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]))

(reg-sub
 :articles/all
 (fn [db]
   (get-in db [:data :articles])))

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
