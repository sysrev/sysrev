(ns sysrev.routes
  (:require
   [sysrev.base :refer
    [st work-state display-state display-ready clear-loading-state
     scroll-top get-scroll-position set-scroll-position active-route]]
   [sysrev.state.core :as st :refer
    [data current-page current-user-id logged-in? current-project-id]]
   [sysrev.state.project :refer [project]]
   [sysrev.ajax :as ajax]
   [sysrev.util :refer [dissoc-in]]
   [sysrev.shared.util :refer [in?]]
   [secretary.core :include-macros true :refer-macros [defroute]]
   [reagent.core :as r])
  (:require-macros [sysrev.macros :refer [with-state using-work-state]]))

(def public-pages
  [:login :register :request-password-reset :reset-password])

(def public-data-fields
  [[:all-projects]])

(defn public-data? [data-key]
  (some #(= % data-key) public-data-fields))

(defn page-authorized? [page]
  (or (some #(= % page) public-pages)
      (and (logged-in?)
           (current-project-id))))

(defn user-labels-path [user-id]
  (when user-id
    (when-let [project-id (current-project-id)]
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
      (with-state s
        [[:project (current-project-id)]
         [:users]]))
    :reload
    (fn [old new]
      (with-state new
        (when-let [project-id (current-project-id)]
          [[:project project-id]])))}

   :project-settings
   {:required
    (fn [s]
      (with-state s
        [[:project (current-project-id)]
         [:project (current-project-id) :settings]
         [:users]]))
    :reload
    (fn [old new]
      (with-state new
        (when-let [project-id (current-project-id)]
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
         [:project (current-project-id) :keywords]]))
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

   :articles
   {:required
    (fn [s]
      (with-state s
        [[:documents]
         [:project (current-project-id) :keywords]
         (user-labels-path (current-user-id))
         (when (and (current-project-id) (project :labels))
           (let [label-id (or (st :page :articles :label-id)
                              (project :overall-label-id))]
             [:label-activity label-id]))
         (when-let [article-id (st :page :articles :modal-article-id)]
           [:article-labels article-id])]))
    :reload
    (fn [old new]
      (with-state new
        [(when (and (current-project-id) (project :labels)
                    (not= (:active-page old) :articles))
           (when-let [label-id (or (st :page :articles :label-id)
                                   (project :overall-label-id))]
             [:label-activity label-id]))
         (when-let [article-id (st :page :articles :modal-article-id)]
           [:article-labels article-id])]))}
   :labels
   {:required
    (fn [s]
      [])}})

(defn global-required-data [s]
  (using-work-state
   (with-state s
     (concat
      [[:all-projects]]
      (when-let [project-id (current-project-id)]
        [[:project project-id]])))))

(defn page-required-data
  ([page-key]
   (page-required-data page-key @work-state))
  ([page-key state-map]
   (let [required-fn (get-in page-specs [page-key :required])]
     (as-> (concat (using-work-state (required-fn state-map))
                   (global-required-data state-map))
         fields
       (remove nil? fields)
       (if (page-authorized? page-key)
         fields
         (filter public-data? fields))))))

(defn page-missing-data
  "Test whether all server data required for a page has been received."
  ([page-key]
   (page-missing-data page-key @work-state))
  ([page-key state-map]
   (let [required-fields (page-required-data page-key state-map)]
     (filter #(= :not-found
                 (get-in state-map (concat [:data] %) :not-found))
             required-fields))))

(defn data-initialized?
  "Test whether all server data required for a page has been received."
  ([page-key]
   (data-initialized? page-key @work-state))
  ([page-key state-map]
   (and (contains? state-map :identity)
        (contains? state-map :current-project-id)
        (empty? (page-missing-data page-key state-map)))))

(defn page-reload-data
  ([page-key]
   (page-reload-data page-key @work-state))
  ([page-key state-map]
   (page-reload-data page-key state-map state-map))
  ([page-key old-state-map new-state-map]
   (when-let [reload-fn (get-in page-specs [page-key :reload])]
     (as-> (using-work-state (reload-fn old-state-map new-state-map))
         fields
       (remove nil? fields)
       (if (page-authorized? page-key)
         fields
         (filter public-data? fields))))))

(defn do-timeout-scroll [pos]
  (set-scroll-position pos)
  (doseq [toffset [1 25 100]]
    (js/setTimeout #(set-scroll-position pos) toffset)))
(defn schedule-scroll [& [pos]]
  (let [pos (or pos 0)]
    (if (data-initialized? (current-page))
      (do-timeout-scroll pos)
      (swap! work-state assoc :scroll-to pos))))
(defn schedule-scroll-top []
  (schedule-scroll 0))

(defn do-route-change
  "This should be called in each route handler, to set the page state
  and fetch data according to the page's entry in `page-specs`."
  [page-key page-map]
  (let [reload-data (page-reload-data
                     page-key
                     @work-state
                     (-> @work-state
                         (assoc-in [:page page-key] page-map)
                         (assoc :active-page page-key)))]
    (swap! work-state
           (comp
            ;; remove existing data for all `reload-data` entries
            (->> reload-data
                 (map (fn [reload-ks]
                        #(dissoc-in % (concat [:data] reload-ks))))
                 (apply comp))
            ;; update page state
            #(assoc-in % [:page page-key] page-map)
            #(assoc % :active-page page-key)
            #(assoc % :uri @active-route)))
    (when-not (contains? @work-state :identity)
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
 work-state :fetch-page-data
 (fn [k v old new]
   (using-work-state
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
         (not= (with-state old (current-project-id))
               (with-state new (current-project-id))))
        (with-state new
          (doall (map ajax/fetch-data (page-required-data page))))
        
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
        
        true nil)))))

;; Update `display-state` value when `data-initialized?` is true.
(add-watch
 work-state :update-display-state
 (fn [k v old new]
   (using-work-state
    (let [page (with-state new (current-page))]
      (let [ready (data-initialized? page new)
            prev-ready (data-initialized? page old)]
        (when ready
          (let [new-display (-> new (dissoc :scroll-to))]
            (reset! display-state new-display)
            (reset! display-ready true)
            (when-let [pos (:scroll-to new)]
              (do-timeout-scroll pos)
              (swap! work-state dissoc :scroll-to))))
        (when (and ready (not prev-ready))
          (clear-loading-state))
        ;; show a loading indicator if waiting for >100ms
        (when (and (not ready) prev-ready)
          (js/setTimeout
           (fn []
             (using-work-state
              (let [newer @work-state
                    page (with-state newer (current-page))]
                (when (not (data-initialized? page newer))
                  (reset! display-ready false)))))
           50)))))))

(defroute home-route "/" []
  (do-route-change :project
                   {:tab :overview}))

(defroute login-route "/login" []
  (do-route-change :login
                   {:email "" :password "" :submit false}))

(defroute register-route "/register" []
  (do-route-change :register
                   {:email "" :password "" :submit false}))

(defroute register-project-route
  "/register/:project-hash" [project-hash]
  (do-route-change :register
                   {:email "" :password "" :submit false
                    :project-hash project-hash}))

(defroute register-project-login-route
  "/register/:project-hash/login" [project-hash]
  (do-route-change :register
                   {:email "" :password "" :submit false
                    :project-hash project-hash
                    :login? true})
  (ajax/set-login-redirect-path
   (str "/register/" project-hash)))

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

(defroute project-settings-route "/project/settings" []
  (do-route-change :project-settings
                   {:active-values (project :settings)}))

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

(defroute classify-route-old "/classify" []
  (do-route-change :classify
                   {:label-values {}}))

(defroute labels-route "/project/labels" []
  (do-route-change :labels {}))

(defroute labels-route-old "/labels" []
  (do-route-change :labels {}))

(defroute articles "/project/articles" []
  (using-work-state
   (let [on-page? (= (current-page) :articles)
         scroll-pos (and on-page? (st :page :articles :scroll-pos))]
     (do-route-change :articles (if on-page?
                                  (merge (st :page :articles)
                                         {:modal-article-id nil
                                          :scroll-pos nil})
                                  {:label-id nil}))
     (when scroll-pos
       (schedule-scroll scroll-pos)))))

(defroute articles-id "/project/articles/:article-id" [article-id]
  (using-work-state
   (let [prev-uri (st :uri)
         article-id (js/parseInt article-id)
         on-page? (= (current-page) :articles)
         scroll-pos (get-scroll-position)]
     (do-route-change :articles (if on-page?
                                  (merge (st :page :articles)
                                         {:modal-article-id article-id
                                          :scroll-pos scroll-pos
                                          :prev-uri nil})
                                  {:label-id nil
                                   :modal-article-id article-id
                                   :scroll-pos scroll-pos
                                   :prev-uri prev-uri}))
     (schedule-scroll 0))))
