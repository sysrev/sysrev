(ns sysrev.state.article
  (:require [re-frame.core :refer [subscribe reg-sub reg-sub-raw reg-event-db trim-v]]
            [sysrev.state.identity :as self]
            [sysrev.data.core :refer [def-data]]))

(defn get-article [db article-id]
  (let [article (get-in db [:data :articles article-id])]
    (when (and (map? article) (not-empty article))
      article)))

(defn load-article [db {:keys [article-id] :as article}]
  (assoc-in db [:data :articles article-id] article))

(defn update-article [db article-id changes]
  (if-let [article (get-article db article-id)]
    (update-in db [:data :articles article-id]
               #(merge % changes))
    db))

(def-data :article
  :loaded? (fn [db project-id article-id]
             (-> (get-in db [:data :articles])
                 (contains? article-id)))
  :uri (fn [project-id article-id]
         (str "/api/article-info/" article-id))
  :prereqs (fn [project-id article-id] [[:project project-id]])
  :content (fn [project-id article-id] {:project-id project-id})
  :process
  (fn [{:keys [db]} [project-id article-id] {:keys [article labels notes]}]
    (let [article (merge article {:labels labels :notes notes})]
      {:db (-> db (load-article article))
       :dispatch [:reload [:annotator/article project-id article-id]]})))

(reg-sub :articles/all #(get-in % [:data :articles]))

(reg-sub :article/raw
         :<- [:articles/all]
         (fn [articles [_ article-id]]
           (get articles article-id)))

(reg-sub :article/sources
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:sources article)))

(reg-sub :article/review-status
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:review-status article)))

(reg-sub :article/title
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:primary-title article)))

(reg-sub :article/journal-name
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:secondary-title article)))

(reg-sub :article/date
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:date article)))

(reg-sub :article/authors
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:authors article)))

(reg-sub :article/abstract
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:abstract article)))

(reg-sub :article/title-render
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:title-render article)))

(reg-sub :article/journal-render
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:journal-render article)))

(reg-sub :article/abstract-render
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:abstract-render article)))

(reg-sub ::urls
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:urls article)))

(reg-sub :article/locations
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:locations article)))

(reg-sub :article/flags
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:flags article)))

(reg-sub :article/score
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:score article)))

(reg-sub :article/duplicates
         (fn [[_ article-id]] (subscribe [:article/flags article-id]))
         (fn [flags [_ article-id]]
           (when-let [flag (get flags "auto-duplicate")]
             {:article-ids (->> flag :meta :duplicate-of (remove #(= % article-id)))
              :disabled? (:disable flag)})))

(defn- article-location-urls [locations]
  (->> (for [source [:pubmed :doi :pii :nct]]
         (let [entries (get locations (name source))]
           (for [{:keys [external-id]} entries]
             (case source
               :pubmed (str "https://www.ncbi.nlm.nih.gov/pubmed/?term=" external-id)
               :doi (str "https://dx.doi.org/" external-id)
               :pmc (str "https://www.ncbi.nlm.nih.gov/pmc/articles/" external-id "/")
               :nct (str "https://clinicaltrials.gov/ct2/show/" external-id)
               nil))))
       (apply concat)
       (remove nil?)))

(reg-sub :article/urls
         (fn [[_ article-id]]
           [(subscribe [::urls article-id])
            (subscribe [:article/locations article-id])])
         (fn [[urls locations]]
           (->> (concat urls (article-location-urls locations))
                (filter #(or (re-matches #"^http://.*" %)
                             (re-matches #"^https://.*" %)
                             (re-matches #"^ftp://.*" %))))))

(defn article-labels [db article-id & [user-id label-id]]
  (when-let [labels (get-in db [:data :articles article-id :labels])]
    (cond-> labels
      user-id                (get user-id)
      (and user-id label-id) (get label-id))))

(reg-sub :article/labels
         (fn [[_ article-id user-id label-id]]
           (subscribe [:article/raw article-id]))
         (fn [article [_ article-id user-id label-id]]
           (cond-> (:labels article)
             user-id                 (get user-id)
             (and user-id label-id)  (get label-id))))

(reg-sub :article/resolve-user-id
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (-> article :resolve :user-id)))

(reg-sub :article/resolve-labels
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (-> article :resolve :labels)))

(defn- article-user-status-impl [user-id ulmap]
  (cond (nil? user-id)  :logged-out
        (empty? ulmap)  :none
        (some :confirmed (vals ulmap)) :confirmed
        :else           :unconfirmed))

(defn article-user-status [db article-id & [user-id]]
  (let [user-id (or user-id (self/current-user-id db))
        ulmap (article-labels db article-id user-id)]
    (article-user-status-impl user-id ulmap)))

(reg-sub :article/user-status
         (fn [[_ article-id user-id]]
           [(subscribe [:self/user-id])
            (subscribe [:article/labels article-id])])
         (fn [[self-id alabels] [_ _ user-id]]
           (let [user-id (or user-id self-id)
                 ulmap (get alabels user-id)]
             (article-user-status-impl user-id ulmap))))

(reg-sub :article/pdfs
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:pdfs article)))

(reg-sub :article/open-access-available?
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:open-access-available? article)))

(reg-sub :article/key
         (fn [[_ article-id]] (subscribe [:article/raw article-id]))
         (fn [article] (:key article)))
