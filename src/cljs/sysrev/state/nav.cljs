(ns sysrev.state.nav
  (:require [re-frame.core :refer
             [subscribe reg-sub reg-event-db reg-event-fx
              dispatch trim-v reg-fx]]
            [sysrev.base :refer [active-route]]
            [sysrev.nav :refer [nav-scroll-top force-dispatch]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.util :refer [dissoc-in]]
            [sysrev.shared.util :refer
             [in? parse-integer integer-project-id?]]))

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

(reg-event-db
 :do-login-redirect
 (fn [db]
   (let [url (get-login-redirect-url db)]
     (nav-scroll-top url)
     (force-dispatch url)
     (dissoc db :login-redirect))))

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

(reg-event-fx
 :navigate
 [trim-v]
 (fn [{:keys [db]} [path params & {:keys [scroll-top?]}]]
   (let [active (active-panel db)]
     {(if scroll-top? :nav-scroll-top :nav)
      (let [uri
            (if (= path (take (count path) active))
              (default-subpanel-uri db path)
              (active-subpanel-uri db path))]
        (if (fn? uri) (uri params) uri))})))

(defn lookup-project-url-id [db url-id]
  (get-in db [:data :project-url-ids url-id] :not-found))

(reg-sub
 :project-url-id
 (fn [db [_ url-id]]
   (lookup-project-url-id db url-id)))

(defn active-project-url [db]
  (get-in db [:state :active-project-url]))
(reg-sub :active-project-url active-project-url)

(defn active-project-id [db]
  (or (get-in db [:state :active-project-literal])
      (some->> (active-project-url db)
               (lookup-project-url-id db)
               (#(if (integer? %) % nil)))))
(reg-sub :active-project-id active-project-id)

(reg-sub
 :recent-active-project
 (fn [db] (get-in db [:state :recent-active-project])))

(reg-event-fx
 :set-active-project-url
 [trim-v]
 (fn [{:keys [db]} [url-id]]
   (let [literal-id (-> (and url-id
                             (integer-project-id? url-id)
                             (parse-integer url-id))
                        (#(if (integer? %) % nil)))
         recent-url (get-in db [:state :recent-project-url])
         cur-active (active-project-id db)
         new-db
         (cond->
             (-> db
                 (assoc-in [:state :active-project-literal] literal-id)
                 (assoc-in [:state :active-project-url] url-id))
             url-id (assoc-in [:state :recent-project-url] url-id))
         new-active (active-project-id new-db)
         new-db (cond-> new-db
                  new-active (assoc-in [:state :recent-active-project] new-active))
         ;; Reset data if this causes changing to a new active project.
         changed? (and recent-url url-id
                       (not= recent-url url-id)
                       (not= cur-active new-active))]
     (cond-> {:db new-db}
       changed?
       (merge {:reset-ui true, :reset-needed true})

       (and url-id (nil? literal-id))
       (merge {:dispatch [:require [:project-url-id url-id]]})

       (nil? new-active)
       (merge {:set-page-title nil})

       new-active
       (merge {:set-page-title (:name (get-project-raw new-db new-active))})))))

(defn project-uri [project-id suburi]
  (let [project-url-id @(subscribe [:project/active-url-id project-id])
        url-id (if (string? project-url-id)
                 project-url-id project-id)]
    (str "/p/" url-id suburi)))

(reg-event-fx
 :project/navigate
 [trim-v]
 (fn [_ [project-id]]
   {:nav-scroll-top (project-uri project-id "")}))

(reg-event-db
 :load-project-url-ids
 [trim-v]
 (fn [db [url-ids-map]]
   (update-in db [:data :project-url-ids]
              #(merge % url-ids-map))))

(def-data :project-url-id
  :loaded? (fn [db url-id]
             (-> (get-in db [:data :project-url-ids])
                 (contains? url-id)))
  :uri (fn [url-id] "/api/lookup-project-url")
  :content (fn [url-id] {:url-id url-id})
  :prereqs (fn [url-id] [])
  :process
  (fn [_ [url-id] {:keys [project-id]}]
    (let [project-id (-> project-id (#(if (integer? %) % nil)))]
      {:dispatch [:load-project-url-ids {url-id project-id}]})))
