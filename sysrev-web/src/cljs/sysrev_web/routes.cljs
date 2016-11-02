(ns sysrev-web.routes
  (:require
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.core :refer [on-page? current-page current-user-id logged-in?]]
   [sysrev-web.state.data :refer [data]]
   [sysrev-web.ajax :as ajax]
   [sysrev-web.util :refer [nav in?]]
   [secretary.core :include-macros true :refer-macros [defroute]]
   [reagent.core :as r])
  (:require-macros [sysrev-web.macros :refer [with-state]]))

(def public-pages
  [:login :register :labels])

(def public-data-fields
  [[:all-projects]])

(defn public-data? [data-key]
  (some #(= % data-key) public-data-fields))

(defn on-public-page? []
  (some on-page? public-pages))

(defn page-authorized? [page]
  (or (some #(= % page) public-pages)
      (logged-in?)))

;; This var records the elements of `(:data @state)` that are required by each
;; page on the web site.
;;
;; `:required` is a function that takes a state map as its argument and returns
;; a vector of required data entries. These will be fetched if they have not
;; been already.
;;
;; `:reload` (optional) returns entries like `:required` and takes two arguments
;; `[old new]` containing the state maps before and after the route change. The
;; entries will be fetched even if they already exist.
;;
(def page-specs
  {:login
   {:required
    (fn [s]
      [[:all-projects]])}

   :register
   {:required
    (fn [s]
      [[:all-projects]])}
   
   :project
   {:required
    (fn [s]
      [[:all-projects]
       [:criteria]
       [:sysrev]])
    :reload
    (fn [old new]
      [[:sysrev]])}
   
   :classify
   {:required
    (fn [s]
      [[:all-projects]
       [:criteria]
       [:documents]
       [:sysrev]
       (when-let [user-id (with-state s (current-user-id))]
         [:users user-id])
       [:classify-article-id]
       (when-let [article-id (-> s :data :classify-article-id)]
         [:article-labels article-id])])
    :reload
    (fn [old new]
      [(when-let [user-id (with-state new (current-user-id))]
         [:users user-id])
       (when-let [article-id (-> new :data :classify-article-id)]
         [:article-labels article-id])])}
   
   :article
   {:required
    (fn [s]
      [[:all-projects]
       [:criteria]
       [:article-labels (-> s :page :article :id)]
       [:documents]
       [:sysrev]
       (when-let [user-id (with-state s (current-user-id))]
         [:users user-id])])
    :reload
    (fn [old new]
      [(when-let [user-id (with-state new (current-user-id))]
         [:users user-id])
       [:article-labels (-> new :page :article :id)]])}
   
   :user-profile
   {:required
    (fn [s]
      [[:all-projects]
       [:criteria]
       [:sysrev]
       [:documents]
       [:users (-> s :page :user-profile :user-id)]])
    :reload
    (fn [old new]
      [[:users (-> new :page :user-profile :user-id)]])}
   
   :labels
   {:required
    (fn [s]
      [[:all-projects]
       [:criteria]])}})

(defn data-initialized?
  "Test whether all server data required for a page has been received."
  [page]
  (and (contains? @state :identity)
       (let [required-fn (get-in page-specs [page :required])
             required-fields
             (as-> (required-fn @state) fields
               (if (page-authorized? page)
                 fields
                 (filter public-data? fields)))]
         (every? #(not= :not-found (data % :not-found))
                 required-fields))))

(defn page-required-data
  ([page-key]
   (page-required-data page-key @state))
  ([page-key state-map]
   (let [required-fn (get-in page-specs [page-key :required])]
     (as-> (required-fn state-map) fields
       (remove nil? fields)
       (if (page-authorized? page-key)
         fields
         (filter public-data? fields))))))

(defn page-reload-data
  ([page-key]
   (page-reload-data page-key @state))
  ([page-key state-map]
   (page-reload-data page-key state-map state-map))
  ([page-key old-state-map new-state-map]
   (when-let [reload-fn (get-in page-specs [page-key :reload])]
     (as-> (reload-fn old-state-map new-state-map) fields
       (remove nil? fields)
       (if (page-authorized? page-key)
         fields
         (filter public-data? fields))))))

(defn do-route-change
  "This should be called in each route handler, to set the page state
  and fetch data according to the page's entry in `page-specs`."
  [page-key page-map]
  (let [old-state @state]
    (swap! state #(-> %
                      (assoc-in [:page page-key] page-map)
                      (assoc :active-page page-key)))
    (when-not (contains? @state :identity)
      (ajax/pull-identity))
    (let [reload-data (page-reload-data page-key old-state @state)]
      (doseq [ks reload-data]
        (when (not= :not-found (data ks :not-found))
          (ajax/fetch-data ks true))))
    (doall (map ajax/fetch-data (page-required-data page-key)))))

;; This monitors for changes in the data requirements of the current page.
;;
;; It will fetch any new entries that appear in the `:required` and `:reload`
;; lists for the page after a state change.
;;
;; This mechanism allows for page data to be fetched sequentially with later
;; requests that may depend on earlier ones; when one request completes and
;; updates `state`, new entries may appear in `:required` and `:reload` based
;; on the results of that request.
(add-watch
 state :fetch-page-data
 (fn [k v old new]
   (let [page (with-state new (current-page))
         old-page (with-state old (current-page))]
     (cond

       ;; Fetch all page data upon login or logout
       (or
        ;; login
        (and (with-state old (not (logged-in?)))
             (with-state new (logged-in?)))
        ;; logout
        (and (with-state old (logged-in?))
             (with-state new (not (logged-in?)))))
       (doall (map ajax/fetch-data (page-required-data page)))
       
       (and page old-page (= page old-page))
       (let [reqs (page-required-data page new)
             old-reqs (page-required-data page old)
             fetch-reqs (->> reqs
                             (remove #(in? old-reqs %)))
             reload-fn (get-in page-specs [page :reload])
             reload-data (page-reload-data page new)
             old-reload-data (page-reload-data page old)
             reloads (->> reload-data
                          (remove #(in? old-reload-data %))
                          (remove #(in? fetch-reqs %)))]
         (doall (map ajax/fetch-data fetch-reqs))
         (doall (map #(ajax/fetch-data % true) reloads)))

       true nil))))

(defroute home "/" []
  (do-route-change :project
                   {:tab :overview}))

(defroute login "/login" []
  (do-route-change :login
                   {:email "" :password "" :submit false}))

(defroute register "/register" []
  (do-route-change :register
                   {:email "" :password "" :submit false}))

(defroute project-overview "/project" []
  (do-route-change :project
                   {:tab :overview}))

(defroute project-predict "/project/predict" []
  (do-route-change :project
                   {:tab :predict}))

(defroute project-predict-cid "/project/predict/:cid" [cid]
  (let [cid (js/parseInt cid)]
    (do-route-change :project
                     {:tab :predict
                      :active-cid cid})))

(defroute self-profile "/user" []
  (do-route-change :user-profile
                   {:self true
                    :user-id (current-user-id)
                    :articles-tab :default}))

(defroute user-profile "/user/:id" [id]
  (let [id (js/parseInt id)]
    (do-route-change :user-profile
                     {:self false
                      :user-id id
                      :articles-tab :default})))

(defroute article "/article/:article-id" [article-id]
  (let [article-id (js/parseInt article-id)]
    (do-route-change :article
                     {:id article-id
                      :label-values {}})))

(defroute classify "/classify" []
  (do-route-change :classify
                   {:label-values {}}))

(defroute labels "/labels" []
  (do-route-change :labels {}))
