(ns sysrev-web.routes
  (:require
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.core :refer [on-page? current-user-id]]
   [sysrev-web.state.data :refer [data]]
   [sysrev-web.ajax :refer [fetch-data pull-identity]]
   [sysrev-web.classify :refer [label-queue label-queue-update]]
   [sysrev-web.util :refer [nav]]
   [secretary.core :include-macros true :refer-macros [defroute]]
   [reagent.core :as r])
  (:require-macros [sysrev-web.macros :refer [with-state]]))

;; This var records the elements of `(:data @state)` that are required by
;; each page on the web site.
;;
;; Each entry can have keys [:required :reload :on-ready]
;; `:required` is a function that takes a state map as its argument
;; and returns a vector of required data entries.
;; These will be fetched if they have not been already.
;;
;; `:reload` returns the same as `:required` but takes two arguments,
;; `[old new]` containing the state maps before and after the route change.
;; The entries will be fetched even if they already exist.
;;
;; `:on-ready` is a function that will be called when the `:required`
;; data entries have all become available.
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
   
   :ranking
   {:required
    (fn [s]
      [[:criteria]
       [:labels]
       [:articles]
       [:sysrev]
       [:ranking]])
    :reload
    (fn [old new] nil)}
   
   :project
   {:required
    (fn [s]
      [[:criteria]
       [:labels]
       [:articles]
       [:sysrev]])
    :reload
    (fn [old new]
      [[:sysrev]])}
   
   :classify
   {:required
    (fn [s]
      [[:criteria]
       [:labels]
       [:articles]
       [:sysrev]
       (when-let [user-id (with-state s (current-user-id))]
         [:users user-id])])
    :reload
    (fn [old new]
      [(when-let [user-id (with-state new (current-user-id))]
         [:users user-id])])}
   
   :article
   {:required
    (fn [s]
      [[:criteria]
       [:labels]
       [:articles (-> s :page :article :id)]
       [:sysrev]
       (when-let [user-id (with-state s (current-user-id))]
         [:users user-id])])
    :reload
    (fn [old new]
      [(when-let [user-id (with-state new (current-user-id))]
         [:users user-id])])}
   
   :user-profile
   {:required
    (fn [s]
      [[:criteria]
       [:sysrev]
       [:labels]
       [:users (-> s :page :user-profile :user-id)]])
    :reload
    (fn [old new]
      [[:users (-> new :page :user-profile :user-id)]])
    :on-ready
    (fn [] nil)}
   
   :labels
   {:required
    (fn [s]
      [[:criteria]])}})

(defn do-route-change
  "This should be called in each route handler, to set the page state
  and fetch data according to the page's entry in `page-specs`."
  [page-key page-map]
  (let [old-state @state]
    (swap! state #(-> %
                      (assoc-in [:page page-key] page-map)
                      (assoc :active-page page-key)))
    (when-not (contains? @state :identity)
      (pull-identity))
    (let [required-fn (get-in page-specs [page-key :required])
          required-data (remove nil? (required-fn @state))]
      (doall (map fetch-data required-data)))
    (when-let [reload-fn (get-in page-specs [page-key :reload])]
      (let [reload-data (remove nil? (reload-fn old-state @state))]
        (doseq [ks reload-data]
          (when (not= :not-found (data ks :not-found))
            (fetch-data ks true)))))
    (when-let [on-ready-fn (get-in page-specs [page-key :on-ready])]
      (on-ready-fn))))

(def public-pages
  [:home :login :register :project :labels :user-profile :article])

(defn on-public-page? []
  (some on-page? public-pages))

(defn data-initialized?
  "Test whether all server data required for a page has been received."
  [page]
  (let [required-fn (get-in page-specs [page :required])
        required-fields (required-fn @state)]
    (every? #(not= :not-found (data % :not-found))
            required-fields)))

(defroute home "/" []
  (nav "/project"))

(defroute ranking "/ranking" []
  (do-route-change :ranking {:ranking-page 0
                             :filters {}}))

(defroute login "/login" []
  (do-route-change :login {:email "" :password "" :submit false}))

(defroute register "/register" []
  (do-route-change :register {:email "" :password "" :submit false}))

(defroute project "/project" []
  (do-route-change :project {}))

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
  (do-route-change :classify {:label-values {}})
  (when (data-initialized? :classify)
    (when (empty? (label-queue))
      (label-queue-update))))

(defroute labels "/labels" []
  (do-route-change :labels {}))
