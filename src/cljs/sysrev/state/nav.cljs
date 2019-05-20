(ns sysrev.state.nav
  (:require [reagent.ratom :refer [reaction]]
            [re-frame.core :refer
             [subscribe reg-sub reg-sub-raw reg-event-db reg-event-fx
              dispatch trim-v reg-fx]]
            [sysrev.base :refer [active-route]]
            [sysrev.nav :refer [nav-scroll-top force-dispatch]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.util :refer [dissoc-in]]
            [sysrev.shared.util :as sutil :refer
             [in? parse-integer ensure-pred filter-values]]))

(defn active-panel [db]
  (get-in db [:state :active-panel]))

(reg-sub :active-panel active-panel)

(defn get-login-redirect-url [db]
  (or (:login-redirect db)
      (let [panel (active-panel db)]
        (if (in? [[:login] [:register]] panel)
          "/" @active-route))))
(reg-sub :login-redirect-url get-login-redirect-url)

(reg-event-db
 :set-login-redirect-url
 [trim-v]
 (fn [db [url]]
   (assoc db :login-redirect url)))

(reg-event-fx
 :do-login-redirect
 (fn [{:keys [db]}]
   (let [url (get-login-redirect-url db)]
     {:nav-reload url})))

(defn panel-prefixes [path]
  (->> (range 1 (inc (count path)))
       (map #(vec (take % path)))
       reverse vec))

(reg-sub
 ::active-subpanels
 (fn [db]
   (get-in db [:state :navigation :subpanels])))

(reg-sub
 ::navigate-defaults
 (fn [db]
   (get-in db [:state :navigation :defaults])))

(defn active-subpanel-uri [db path]
  (or (get-in db [:state :navigation :subpanels path])
      (get-in db [:state :navigation :defaults path])
      path))

(defn default-subpanel-uri [db path]
  (get-in db [:state :navigation :defaults path]))

(defn set-active-subpanel [db prefix uri]
  (assoc-in db [:state :navigation :subpanels (vec prefix)] uri))

(defn set-subpanel-default-uri [db prefix uri]
  (assoc-in db [:state :navigation :defaults (vec prefix)] uri))

(reg-event-db
 :set-active-subpanel
 [trim-v]
 (fn [db [prefix uri]]
   (set-active-subpanel db prefix uri)))

(reg-event-fx
 :set-active-panel
 [trim-v]
 (fn [{:keys [db]} [panel uri]]
   (let [active (active-panel db)
         uri (or uri (default-subpanel-uri db panel))]
     {:db (assoc-in db [:state :active-panel] panel)
      :dispatch-n
      (concat
       [[:review/reset-ui-labels]
        [:review/reset-ui-notes]
        [:reset-transient-fields panel]]
       (->> (panel-prefixes panel)
            (map (fn [prefix]
                   [:set-active-subpanel prefix uri]))))})))

;; TODO: remove this subpanel logic? (not used now)
(reg-event-fx
 :navigate
 [trim-v]
 (fn [{:keys [db]} [path params & {:keys [scroll-top?]}]]
   (let [active (active-panel db)]
     {(if scroll-top? :nav-scroll-top :nav)
      (let [uri (if (= path (take (count path) active))
                  (default-subpanel-uri db path)
                  (active-subpanel-uri db path))]
        (if (fn? uri) (uri params) uri))})))

(defn lookup-project-url [db url-id]
  (when url-id (get-in db [:data :project-lookups url-id] :loading)))

(reg-sub :lookup-project-url
         (fn [db [_ url-id]]
           (lookup-project-url db url-id)))

(defn active-project-url [db] (get-in db [:state :active-project-url]))

(reg-sub :active-project-url active-project-url)

;; checks if current project url should be redirected
;; (TODO: handle redirect on ownership transfer?)
(reg-sub :project-redirect-needed
         (fn [db]
           (let [url-id (active-project-url db)
                 [project-id-url owner-url] url-id
                 owned-url? (and project-id-url owner-url)
                 full-id (some->> url-id
                                  (lookup-project-url db)
                                  (ensure-pred map?))
                 project-id-full (ensure-pred integer? (:project-id full-id))
                 user-id-full (ensure-pred integer? (:user-id full-id))
                 org-id-full (ensure-pred integer? (:org-id full-id))
                 full-id-valid? (and project-id-full (or user-id-full org-id-full))]
             ;; redirect on legacy url for owned project
             (when (and (parse-integer project-id-url)
                        (nil? owner-url)
                        full-id-valid?)
               project-id-full))))

(defn active-project-id [db]
  (let [url-id (get-in db [:state :active-project-url])]
    (some->> (lookup-project-url db url-id)
             (ensure-pred map?)
             :project-id
             (ensure-pred integer?))))

(reg-sub :active-project-id active-project-id)

(reg-sub :recent-active-project #(get-in % [:state :recent-active-project]))

(reg-event-db :set-project-url-error
              (fn [db [_ error?]]
                (assoc-in db [:state :active-project-url-error] (boolean error?))))

(reg-event-fx
 :set-active-project-url
 (fn [{:keys [db]} [_ [project-url-id {:keys [user-url-id org-url-id] :as owner}]]]
   (let [;; url-id - vector used to look up project id from url strings
         ;; (will be nil when this is called for a non-project url)
         url-id (when project-url-id [project-url-id owner])
         ;; get any integer values from url id strings
         literal-ids (->> {:project (parse-integer project-url-id)
                           :user (parse-integer user-url-id)
                           :org-url-id (parse-integer org-url-id)}
                          (filter-values integer?))
         ;; most recent non-nil value for url-id
         recent-url-id (get-in db [:state :recent-project-url])
         ;; active project id before updating for url
         cur-active (active-project-id db)
         ;; new value for re-frame db map
         new-db (as-> db new-db
                  ;; store id literals from url to state
                  (assoc-in new-db [:state :active-project-literal] literal-ids)
                  ;; store project/owner id strings from url to state
                  (assoc-in new-db [:state :active-project-url] url-id)
                  ;; update value of most recent non-nil url id
                  (cond-> new-db url-id (assoc-in [:state :recent-project-url] url-id))
                  ;; update value of most recent non-nil project id
                  (if-let [new-active (active-project-id new-db)]
                    (assoc-in new-db [:state :recent-active-project] (active-project-id new-db))
                    new-db))
         ;; active project id after updating for url
         new-active (active-project-id new-db)]
     (cond-> {:db new-db
              ;; update browser page title based on project name
              :set-page-title (some->> new-active (get-project-raw new-db) :name)}
       ;; reset data if this changes to a new active project
       (and (not= cur-active new-active) recent-url-id url-id (not= recent-url-id url-id))
       (merge {:reset-ui true, :reset-needed true})
       ;; look up project id from server
       url-id
       (merge {:dispatch [:require [:lookup-project-url url-id]]})))))

(reg-sub-raw :project/uri
             (fn [_ [_ project-id suburi]]
               (reaction
                (let [active-id @(subscribe [:active-project-id])
                      project-id (or project-id active-id)
                      active-url-id @(subscribe [:active-project-url])
                      active-full-id (when active-url-id
                                       @(subscribe [:lookup-project-url active-url-id]))
                      {:keys [user-id org-id]} active-full-id
                      project-url-id (or (->> @(subscribe [:project/active-url-id project-id])
                                              (ensure-pred string?))
                                         project-id)]
                  (str (cond user-id  (str "/u/" user-id)
                             org-id   (str "/o/" org-id)
                             :else    "")
                       "/p/" project-url-id (or suburi ""))))))

(defn project-uri [project-id & [suburi]]
  @(subscribe [:project/uri project-id suburi]))

;; TODO: add this function for use in re-frame events
#_ (defn get-project-uri [db project-id suburi]
     (let [project-url-id (project-active-url-id db project-id)
           url-id (if (string? project-url-id)
                    project-url-id project-id)]
       (str "/p/" url-id suburi)))

(reg-event-fx :project/navigate
              (fn [_ [_ project-id]]
                {:nav-scroll-top (project-uri project-id "")}))

(reg-event-db :load-project-url-ids
              (fn [db [_ url-ids-map]]
                (update-in db [:data :project-url-ids] #(merge % url-ids-map))))

(reg-event-db :load-project-lookup
              (fn [db [_ url-id project-full-id]]
                (update-in db [:data :project-lookups] #(assoc % url-id project-full-id))))

(def-data :lookup-project-url
  :loaded? (fn [db url-id] (-> (get-in db [:data :project-lookups])
                               (contains? url-id)))
  :prereqs (fn [_] [[:identity]])
  :uri (fn [url-id] "/api/lookup-project-url")
  :content (fn [url-id] {:url-id (sutil/write-transit-str url-id)})
  :process (fn [_ [url-id] project-full-id]
             #_ (println (pr-str {:project-full-id project-full-id}))
             {:dispatch [:load-project-lookup url-id project-full-id]}))
