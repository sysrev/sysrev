(ns sysrev.state.nav
  (:require [re-frame.core :refer
             [reg-event-db reg-event-fx reg-sub
                                   reg-sub-raw subscribe trim-v]]
            [reagent.ratom :refer [reaction]]
            [sysrev.base :refer [active-route]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.project.base :refer [get-project-raw]]
            [sysrev.util :as util :refer
             [filter-values in? parse-integer when-test]]))

(defn active-panel [db]
  (get-in db [:state :active-panel]))

(reg-sub :active-panel active-panel)

(defn get-login-redirect-url [db]
  (or (:login-redirect db)
      (let [panel (active-panel db)]
        (if (in? [[:login] [:register]] panel)
          "/" @active-route))))

(reg-sub :login-redirect-url get-login-redirect-url)

(reg-event-db :set-login-redirect-url [trim-v]
              (fn [db [url]] (assoc db :login-redirect url)))

(reg-event-fx :do-login-redirect
              (fn [{:keys [db]}]
                {:load-url [(get-login-redirect-url db)]}))

(reg-event-fx :set-active-panel [trim-v]
              (fn [{:keys [db]} [panel]]
                {:db (assoc-in db [:state :active-panel] panel)
                 :dispatch-n (concat [[:review/reset-ui-labels]
                                      [:review/reset-ui-notes]
                                      [:reset-transient-fields panel]
                                      (when (= panel [:project :review])
                                        [:review/set-default-values])])}))

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
                 full-id (some->> url-id
                                  (lookup-project-url db)
                                  (when-test map?))
                 project-id-full (when-test integer? (:project-id full-id))
                 user-id-full (when-test integer? (:user-id full-id))
                 org-id-full (when-test integer? (:org-id full-id))
                 full-id-valid? (and project-id-full (or user-id-full org-id-full))]
             ;; redirect on legacy url for owned project
             (when (and (parse-integer project-id-url)
                        (nil? owner-url)
                        full-id-valid?)
               project-id-full))))

(defn active-project-id [db]
  (let [url-id (get-in db [:state :active-project-url])]
    (some->> (lookup-project-url db url-id)
             (when-test map?)
             :project-id
             (when-test integer?))))

(reg-sub :active-project-id active-project-id)

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
         ;; active project id before updating for url
         cur-active (active-project-id db)
         cur-active-url (get-in db [:state :active-project-url])
         ;; new value for re-frame db map
         new-db (as-> db new-db
                  ;; store id literals from url to state
                  (assoc-in new-db [:state :active-project-literal] literal-ids)
                  ;; store project/owner id strings from url to state
                  (assoc-in new-db [:state :active-project-url] url-id)
                  ;; update value of most recent non-nil url id
                  (cond-> new-db url-id (assoc-in [:state :recent-project-url] url-id)))
         ;; active project id after updating for url
         new-active (active-project-id new-db)]
     (cond-> {:db new-db
              ;; update browser page title based on project name
              :set-page-title (some->> new-active (get-project-raw new-db) :name)}
       (and url-id (not= url-id cur-active-url) (not= cur-active new-active))
       (merge {:reset-project-ui true, :reset-needed true})
       ;; look up project id from server
       url-id
       (merge {:dispatch [:require [:lookup-project-url url-id]]})))))

(reg-sub-raw :project/uri
             (fn [_ [_ project-id suburi]]
               (reaction
                (let [active-id @(subscribe [:active-project-id])
                      project-id (or project-id active-id)
                      active-url-id @(subscribe [:active-project-url])
                      active-full-id (when (and active-url-id (= project-id active-id))
                                       @(subscribe [:lookup-project-url active-url-id]))
                      {:keys [user-id org-id]} active-full-id]
                  (str (cond user-id  (str "/u/" user-id)
                             org-id   (str "/o/" org-id)
                             :else    "")
                       "/p/" project-id (or suburi ""))))))

(defn project-uri [project-id & [suburi]]
  @(subscribe [:project/uri project-id suburi]))

(defn user-uri [user-id]
  (str "/user/" user-id "/profile"))

(defn group-uri [group-id]
  (str "/org/" group-id "/users"))

(reg-event-fx :project/navigate
              (fn [_ [_ project-id & [suburi & {:as opts}]]]
                {:nav (into [(project-uri project-id suburi)]
                            (apply concat opts))}))

(reg-event-db :load-project-url-ids
              (fn [db [_ url-ids-map]]
                (update-in db [:data :project-url-ids] #(merge % url-ids-map))))

(reg-event-db :load-project-lookup
              (fn [db [_ url-id project-full-id]]
                (update-in db [:data :project-lookups] #(assoc % url-id project-full-id))))

(def-data :lookup-project-url
  :loaded? (fn [db url-id] (-> (get-in db [:data :project-lookups])
                               (contains? url-id)))
  :uri (fn [_] "/api/lookup-project-url")
  :content (fn [url-id] {:url-id (util/write-transit-str url-id)})
  :process (fn [_ [url-id] project-full-id]
             {:dispatch [:load-project-lookup url-id project-full-id]}))
