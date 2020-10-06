(ns sysrev.views.panels.search
  (:require [clojure.string :as str]
            [ajax.core :refer [GET]]
            [goog.uri.utils :as uri-utils]
            [reagent.core :as r]
            [re-frame.core :refer [dispatch]]
            [sysrev.base :refer [active-route]]
            [sysrev.markdown :as markdown]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [project-uri user-uri]]
            [sysrev.views.panels.user.profile :refer [ProfileAvatar]]
            [sysrev.views.semantic :refer
             [Form Input Loader Divider Grid Row Column Menu MenuItem Label Pagination]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [def-panel]]))

(def state (r/atom {:search-results {}
                    :search-value ""}))

(defn search-url
  "Create a url for search term q of optional type"
  [q & {:keys [type]
        :or {type "projects"}}]
  (nav/make-url "/search" {:q q
                           :p 1
                           :type type}))

(defn site-search [q p]
  (let [search-results (r/cursor state [:search-results])
        p (str p)]
    (swap! search-results assoc-in [q p] :retrieving)
    (GET "/api/search"
         {:params {:q q :p p}
          :handler (fn [response]
                     (swap! search-results assoc-in [q p]
                            (get-in response [:result :results])))
          :error-handler (fn [_response]
                           (swap! search-results assoc-in [q p]
                                  "There was an error retrieving results, please try again"))})))

(defn SiteSearch []
  (let [search-value (r/cursor state [:search-value])
        cleared? (r/atom false)]
    (fn []
      (let [route-search-term (uri-utils/getParamValue @active-route "q")]
        ;; when the route-search-term is empty, clear the search value
        (when (and (empty? route-search-term)
                   (not @cleared?))
          (reset! cleared? true)
          (reset! search-value ""))
        ;; when the route-search-term is present, reset the fact that the searh value
        ;; has been cleared
        (when (seq route-search-term)
          (reset! cleared? false))
        [:div.item
         [Form {:id "search-sysrev-form"
                :on-submit (fn [_e]
                             (if (str/blank? @search-value)
                               (reset! search-value "")
                               (do (site-search @search-value 1)
                                   (nav/nav "/search" :params {:q @search-value
                                                               :p 1
                                                               :type "projects"}))))}
          [Input {:id "search-sysrev-bar"
                  :size "small"
                  :placeholder "Search Sysrev"
                  :action {:type "submit" :size "small" :icon "search" :class "subtle-button"}
                  :on-change (util/on-event-value #(reset! search-value %))
                  :value @search-value}]]]))))

(defn ProjectSearchResult
  [{:keys [project-id description name]}]
  (let [description-string (-> description markdown/create-markdown-html markdown/html->string)
        token-string (str/split description-string #" ")
        max-length 30]
    [:div
     [:a {:href (project-uri project-id)} [:h3 name]]
     [:p (str (->> (take max-length token-string) (str/join " "))
              (when (> (count token-string) max-length)
                " ..."))]
     [Divider]]))

(defn UserSearchResult
  [{:keys [user-id username introduction]}]
  [:div
   [Grid {:columns "equal"}
    [Row
     [Column {:widescreen 1
              :tablet 2
              :mobile 4}
      [:a {:href (user-uri user-id)}
       [ProfileAvatar {:user-id user-id}]]]
     [Column {:style {:padding-left 0}}
      [:a {:href (user-uri user-id)}
       [:h3 {:style {:display "inline"
                     :vertical-align "top"}} username]]
      [:p (-> introduction markdown/create-markdown-html markdown/html->string)]]]]
   [Divider]])

(defn OrgSearchResult
  [{:keys [group-id group-name]}]
  [:div
   [:a {:href (str "/org/" group-id "/projects")}
    [:h3 group-name]
    [Divider]]])

(defn SearchResults [{:keys [q p type]}]
  (reset! (r/cursor state [:search-value]) q)
  (site-search q p)
  (r/create-class
   {:reagent-render
    (fn [{:keys [q p type]}]
      (let [search-results (r/cursor state [:search-results q p])
            projects-count (r/cursor search-results [:projects :count])
            users-count (r/cursor search-results [:users :count])
            orgs-count (r/cursor search-results [:orgs :count])
            page-size 10
            active (if (contains? #{:projects :users :orgs} (keyword type))
                     (keyword type)
                     :projects)
            base-menu-width 3]
        (when (nil? @search-results)
          (site-search q p))
        [Grid {:columns "equal"}
         [Row
          [Column {:widescreen base-menu-width
                   :tablet (+ base-menu-width 1)
                   :mobile (+ base-menu-width 2)}
           [Menu {:secondary true
                  :vertical true
                  :fluid true}
            [MenuItem {:name "Projects"
                       :active (= active :projects)
                       :on-click #(nav/nav "/search" :params {:q q :p 1 :type "projects"})
                       :color "orange"}
             "Projects"
             [Label {:size "mini"
                     :id "search-results-projects-count"} @projects-count]]
            [MenuItem {:name "Users"
                       :active (= active :users)
                       :on-click #(nav/nav "/search" :params {:q q :p 1 :type "users"})
                       :color "orange"}
             "Users"
             [Label {:size "mini"
                     :id "search-results-users-count"} @users-count]]
            [MenuItem {:name "Orgs"
                       :active (= active :orgs)
                       :on-click #(nav/nav "/search" :params {:q q :p 1 :type "orgs"})
                       :color "orange"}
             "Orgs"
             [Label {:size "mini"
                     :id "search-results-orgs-count"} @orgs-count]]]]
          [Column
           [:div
            (if (= @search-results :retrieving)
              [Loader {:active true}]
              (let [items (get-in @search-results [active :items])
                    count (get-in @search-results [active :count])]
                (if-not (empty? items)
                  [:div (doall
                         (for [item items]
                           (condp = active
                             :projects
                             ^{:key (:project-id item)}  [ProjectSearchResult item]
                             :users
                             ^{:key (:user-id item)}     [UserSearchResult item]
                             :orgs
                             ^{:key (:group-id item)}    [OrgSearchResult item]
                             [:h3 "Error: No such key"])))
                   (when (> count 10)
                     [Pagination
                      {:total-pages (+ (quot count page-size)
                                       (if (> (rem count page-size) 0)
                                         1 0))
                       :active-page (or p 1)
                       :on-page-change
                       (fn [_event data]
                         (let [{:keys [activePage]} (js->clj data :keywordize-keys true)]
                           (site-search q activePage)
                           (nav/nav "/search" :params {:q q
                                                       :p activePage
                                                       :type (clj->js active)})))}])]
                  [:div [:h3 (str "We couldn't find anything in "
                                  (condp = active
                                    :projects "Public Projects"
                                    :users "Sysrev Users"
                                    :orgs "Sysrev Orgs")
                                  " matching '" q "'")]
                   (when (= active :projects)
                     [:div [:h3 "Try searching for "
                            [:a {:href (search-url "cancer")} "\"cancer\""]
                            " or "
                            [:a {:href (search-url "genes")} "\"genes\""]]])])))]]]]))
    :component-did-mount (fn [_this] (site-search q p))}))

(defn- Panel []
  [SearchResults {:q (uri-utils/getParamValue @active-route "q")
                  :p (uri-utils/getParamValue @active-route "p")
                  :type (uri-utils/getParamValue @active-route "type")}])

(def-panel :uri "/search" :panel [:search]
  :on-route (dispatch [:set-active-panel [:search]])
  :content [Panel])
