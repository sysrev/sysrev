(ns sysrev.views.search.core
  (:require [ajax.core :refer [GET]]
            [goog.uri.utils :as uri-utils]
            [reagent.core :as r]
            [sysrev.base :refer [active-route]]
            [sysrev.markdown :as markdown]
            [sysrev.nav :refer [nav-scroll-top]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.user.profile :refer [Avatar UserPublicProfileLink]]
            [sysrev.views.semantic :refer [Form Input Loader Divider Grid Row Column Menu MenuItem Image]]))

(def ^:private panel [:search])

(def state (r/atom {:search-results {}
                    :search-value ""
                    :active-search-item :users}))

(defn project-search [q]
  (let [search-results (r/cursor state [:search-results])]
    (swap! search-results assoc q :retrieving)
    (GET "/api/search"
         {:params {:q q}
          :handler (fn [response]
                     (swap! search-results assoc q (get-in response [:result :results])))
          :error-handler (fn [response]
                           (swap! search-results assoc q "There was an error retrieving results, please try again"))})))

(defn SiteSearch []
  (let [search-value (r/cursor state [:search-value])
        cleared? (r/atom false)]
    (r/create-class
     {:reagent-render
      (fn [args]
        (let [route-search-term (uri-utils/getParamValue @active-route "q")]
          ;; when the route-search-term is empty, clear the search value
          (when (and (empty? route-search-term)
                     (not @cleared?))
            (reset! cleared? true)
            (reset! search-value ""))
          ;; when the route-search-term is present, reset the fact that the searh value
          ;; has been cleared
          (when (not (empty? route-search-term))
            (reset! cleared? false))
          [Form {:on-submit (fn [e]
                              (project-search @search-value)
                              (nav-scroll-top "/search" :params {:q @search-value}))}
           [Input {:placeholder "Search Sysrev"
                   :on-change (fn [e value]
                                (let [input-value (-> value
                                                      (js->clj :keywordize-keys true)
                                                      :value)]
                                  (reset! search-value input-value)))
                   :id "search-sysrev-bar"
                   :value @search-value}]]))})))

(defn ProjectSearchResult
  [{:keys [project-id description name]}]
  [:div
   [:a {:href (project-uri project-id)
        ;; :on-click (fn [e]
        ;;             (reset! (r/cursor state [:search-value]) ""))
        } [:h3 name]]
   [:p (-> description markdown/create-markdown-html markdown/html->string)]
   [Divider]])

(defn UserSearchResult
  [{:keys [user-id username introduction]}]
  [:div
   [Grid {:columns "equal"}
    [Row
     [Column {:width 1
              :widescreen 1
              :tablet 2
              :mobile 4}
      [:a {:href (str "/user/" user-id "/profile")}
       [Image {:src (str "/api/user/" user-id "/avatar")
               :avatar true
               :style {:height "4em"
                       :width "4em"}
               :alt ""}]]]
     [Column {:style {:padding-left 0}}
      [:a {:href (str "/user/" user-id "/profile")}
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

(defn SearchResults [{:keys [q]}]
  (reset! (r/cursor state [:search-value]) q)
  (project-search q)
  (r/create-class
   {:reagent-render
    (fn [{:keys [q]}]
      (let [search-results (r/cursor state [:search-results q])
            ;;projects (r/cursor search-results [:projects])
            active (r/cursor state [:active-search-item])]
        [Grid {:columns "equal"}
         [Row
          [Column {:width 2
                   :widescreen 2
                   :tablet 3
                   :mobile 5}
           [Menu {;;:pointing true
                  :secondary true
                  :vertical true
                  :fluid true}
            [MenuItem {:name "Projects"
                       :active (= @active :projects)
                       :on-click (fn [e]
                                   (reset! active :projects))
                       :color "orange"}]
            [MenuItem {:name "Users"
                       :active (= @active :users)
                       :on-click (fn [e]
                                   (reset! active :users))
                       :color "orange"}]
            [MenuItem {:name "Orgs"
                       :active (= @active :orgs)
                       :on-click (fn [e]
                                   (reset! active :orgs))
                       :color "orange"}]]]
          [Column #_{:width 14
                     :tablet 9
                     :mobile 10
                     :widescreen 14}
           [:div
            ;;[:h1 "Results for " q ":"]
            (if (= @search-results :retrieving)
              [Loader {:active true}]
              #_
              (if-not (empty? @projects)
                (doall
                 (for [project @projects]
                   ^{:key (:project-id project)}
                   [ProjectSearchResult project]))
                [:div
                 [:h3 "No Results Found"]])
              (let [results (get @search-results @active)]
                (if-not (empty? results)
                  (doall
                   (for [result results]
                     (condp = @active
                       :projects
                       ^{:key (:project-id result)}
                       [ProjectSearchResult result]
                       :users
                       ^{:key (:user-id result)}
                       [UserSearchResult result]
                       :orgs
                       ^{:key (:group-id result)}
                       [OrgSearchResult result]
                       [:h3 "Error: No such key"])))
                  [:div [:h3 "No Results Found"]])))]]]]))}))

(defmethod panel-content [:search] []
  (fn [child]
    [:div
     ;;[SiteSearch]
     [SearchResults {:q (uri-utils/getParamValue @active-route "q")}]]))



