(ns sysrev.routes
  (:require
   [sysrev.base :refer [state]]
   [sysrev.state.core :as s :refer
    [on-page? current-page current-user-id logged-in? active-project-id]]
   [sysrev.state.data :as d :refer [data]]
   [sysrev.ajax :as ajax]
   [sysrev.util :refer [nav in? dissoc-in]]
   [secretary.core :include-macros true :refer-macros [defroute]]
   [reagent.core :as r])
  (:require-macros [sysrev.macros :refer [with-state]]))

(def public-pages
  [:login :register :request-password-reset :reset-password :labels])

(def public-data-fields
  [[:all-projects]])

(defn public-data? [data-key]
  (some #(= % data-key) public-data-fields))

(defn on-public-page? []
  (some on-page? public-pages))

(defn page-authorized? [page]
  (or (some #(= % page) public-pages)
      (and (logged-in?)
           (active-project-id))))

(defn user-labels-path [user-id]
  (when user-id
    (when-let [project-id (active-project-id)]
      [:project project-id :member-labels user-id])))

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
      [])}

   :register
   {:required
    (fn [s]
      [])}

   :request-password-reset
   {:required
    (fn [s]
      [])}

   :reset-password
   {:required
    (fn [s]
      [[:reset-code
        (-> s :page :reset-password :reset-code)
        :email]])}

   :select-project
   {:required
    (fn [s]
      [])}
   
   :project
   {:required
    (fn [s]
      [])
    :reload
    (fn [old new]
      (with-state new
        (when-let [project-id (active-project-id)]
          [[:project project-id]])))}
   
   :classify
   {:required
    (fn [s]
      (with-state s
        [[:documents]
         (user-labels-path (current-user-id))
         [:classify-article-id]
         (when-let [article-id (-> s :data :classify-article-id)]
           [:article-labels article-id])]))
    :reload
    (fn [old new]
      (with-state new
        [(user-labels-path (current-user-id))
         (when-let [article-id (-> new :data :classify-article-id)]
           [:article-labels article-id])]))}
   
   :article
   {:required
    (fn [s]
      (with-state s
        [[:documents]
         [:article-labels (-> s :page :article :id)]
         (user-labels-path (current-user-id))
         [:project (active-project-id) :keywords]]))
    :reload
    (fn [old new]
      (with-state new
        [[:article-labels (-> new :page :article :id)]
         (user-labels-path (current-user-id))]))}
   
   :user-profile
   {:required
    (fn [s]
      (with-state s
        [[:documents]
         (user-labels-path (-> s :page :user-profile :user-id))]))
    :reload
    (fn [old new]
      (with-state new
        [(user-labels-path (-> new :page :user-profile :user-id))]))}
   
   :labels
   {:required
    (fn [s]
      [])}})

(defn global-required-data [s]
  (with-state s
    (concat
     [[:all-projects]]
     (when-let [project-id (active-project-id)]
       [[:project project-id]]))))

(defn page-required-data
  ([page-key]
   (page-required-data page-key @state))
  ([page-key state-map]
   (let [required-fn (get-in page-specs [page-key :required])]
     (as-> (concat (required-fn state-map)
                   (global-required-data state-map))
         fields
       (remove nil? fields)
       (if (page-authorized? page-key)
         fields
         (filter public-data? fields))))))

(defn data-initialized?
  "Test whether all server data required for a page has been received."
  [page]
  (and (contains? @state :identity)
       (let [required-fields (page-required-data page)]
         (every? #(not= :not-found (data % :not-found))
                 required-fields))))

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
  (let [reload-data (page-reload-data
                     page-key
                     @state
                     (-> @state
                         (assoc-in [:page page-key] page-map)
                         (assoc :active-page page-key)))]
    (swap! state
           (comp
            ;; remove existing data for all `reload-data` entries
            (->> reload-data
                 (map (fn [reload-ks]
                        #(dissoc-in % (concat [:data] reload-ks))))
                 (apply comp))
            ;; update page state
            #(assoc-in % [:page page-key] page-map)
            #(assoc % :active-page page-key)))
    (when-not (contains? @state :identity)
      (ajax/pull-identity))
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

       ;; Fetch all page data upon login or logout or project change
       (or
        ;; login
        (and (with-state old (not (logged-in?)))
             (with-state new (logged-in?)))
        ;; logout
        (and (with-state old (logged-in?))
             (with-state new (not (logged-in?))))
        ;; project change
        (not= (with-state old (active-project-id))
              (with-state new (active-project-id))))
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

(defroute home-route "/" []
  (do-route-change :project
                   {:tab :overview}))

(defroute login-route "/login" []
  (do-route-change :login
                   {:email "" :password "" :submit false}))

(defroute register-route "/register" []
  (do-route-change :register
                   {:email "" :password "" :submit false}))

(defroute register-project-route "/register/:project-hash" [project-hash]
  (do-route-change :register
                   {:email "" :password "" :submit false
                    :project-hash project-hash}))

(defroute request-password-reset-route "/request-password-reset" []
  (do-route-change :request-password-reset
                   {:email "" :submit false :sent nil}))

(defroute reset-password-route "/reset-password/:reset-code" [reset-code]
  (do-route-change :reset-password
                   {:password ""
                    :reset-code reset-code}))

(defroute select-project-route "/select-project" []
  (do-route-change :select-project
                   {:selected nil}))

(defroute project-overview-route "/project" []
  (do-route-change :project
                   {:tab :overview}))

(defroute project-predict-route "/project/predict" []
  (do-route-change :project
                   {:tab :predict}))

(defroute project-predict-label-id-route "/project/predict/:label-id" [label-id]
  (do-route-change :project
                   {:tab :predict
                    :active-label-id label-id}))

(defroute self-profile-route "/user" []
  (do-route-change :user-profile
                   {:self true
                    :user-id (current-user-id)
                    :articles-tab :default}))

(defroute user-profile-route "/user/:id" [id]
  (let [id (js/parseInt id)]
    (do-route-change :user-profile
                     {:self false
                      :user-id id
                      :articles-tab :default})))

(defroute article-route "/article/:article-id" [article-id]
  (let [article-id (js/parseInt article-id)]
    (do-route-change :article
                     {:id article-id
                      :label-values {}})))

(defroute classify-route "/project/classify" []
  (do-route-change :classify
                   {:label-values {}}))

(defroute labels-route "/project/labels" []
  (do-route-change :labels {}))
