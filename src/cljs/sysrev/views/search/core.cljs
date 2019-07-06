(ns sysrev.views.search.core
  (:require [ajax.core :refer [GET]]
            [goog.uri.utils :as uri-utils]
            [reagent.core :as r]
            [sysrev.base :refer [active-route]]
            [sysrev.markdown :as markdown]
            [sysrev.nav :refer [nav-scroll-top make-url]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.panels.user.profile :refer [ProfileAvatar UserPublicProfileLink]]
            [sysrev.views.semantic :refer [Form Input Loader Divider Grid Row Column Menu MenuItem Image Label Pagination]]))

(def ^:private panel [:search])

(def state (r/atom {:search-results {}
                    :search-value ""}))

(defn search-url
  "Create a url for search term q of optional type"
  [q & {:keys [type]
        :or {type "projects"}}]
  (make-url "/search" {:q q
                       :p 1
                       :type type}))

(defn site-search [q p]
  (let [search-results (r/cursor state [:search-results])
        p (str p)]
    (swap! search-results assoc-in [q p] :retrieving)
    (GET "/api/search"
         {:params {:q q :p p}
          :handler (fn [response]
                     (swap! search-results assoc-in [q p] (get-in response [:result :results])))
          :error-handler (fn [response]
                           (swap! search-results assoc-in [q p] "There was an error retrieving results, please try again"))})))

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
                              (if (clojure.string/blank? @search-value)
                                (reset! search-value "")
                                (do (site-search @search-value 1)
                                    (nav-scroll-top "/search" :params {:q @search-value
                                                                       :p 1
                                                                       :type "projects"}))))
                 :id "search-sysrev-form"}
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
  (let [description-string (-> description markdown/create-markdown-html markdown/html->string)
        token-string (clojure.string/split description-string #" ")
        max-length 30]
    [:div
     [:a {:href (project-uri project-id)} [:h3 name]]
     [:p (str (->> (take max-length token-string)
                   (clojure.string/join " "))
              (when (> (count token-string) max-length)
                " ..."))]
     [Divider]]))

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
       [ProfileAvatar {:user-id user-id}]]]
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
          [Column {:width base-menu-width
                   :widescreen base-menu-width
                   :tablet (+ base-menu-width 1)
                   :mobile (+ base-menu-width 2)}
           [Menu {:secondary true
                  :vertical true
                  :fluid true}
            [MenuItem {:name "Projects"
                       :active (= active :projects)
                       :on-click (fn [e]
                                   (nav-scroll-top "/search" :params {:q q :p 1 :type "projects"}))
                       :color "orange"}
             "Projects"
             [Label {:size "mini"
                     :id "search-results-projects-count"} @projects-count]]
            [MenuItem {:name "Users"
                       :active (= active :users)
                       :on-click (fn [e]
                                   (nav-scroll-top "/search" :params {:q q :p 1 :type "users"}))
                       :color "orange"}
             "Users"
             [Label {:size "mini"
                     :id "search-results-users-count"} @users-count]]
            [MenuItem {:name "Orgs"
                       :active (= active :orgs)
                       :on-click (fn [e]
                                   (nav-scroll-top "/search" :params {:q q :p 1 :type "orgs"}))
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
                             ^{:key (:project-id item)}
                             [ProjectSearchResult item]
                             :users
                             ^{:key (:user-id item)}
                             [UserSearchResult item]
                             :orgs
                             ^{:key (:group-id item)}
                             [OrgSearchResult item]
                             [:h3 "Error: No such key"])))
                   (when (> count 10)
                     [Pagination {:default-active-page 1
                                  :total-pages (+ (quot count page-size) (if (> (rem count page-size) 0)
                                                                           1 0))
                                  :active-page p
                                  :on-page-change (fn [event data]
                                                    (let [new-active-page (:activePage (js->clj data :keywordize-keys true))]
                                                      (site-search q new-active-page)
                                                      (nav-scroll-top "/search" :params {:q q
                                                                                         :p new-active-page
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
    :component-did-mount (fn [this]
                           (site-search q p))}))

(defmethod panel-content [:search] []
  (fn [child]
    [SearchResults {:q (uri-utils/getParamValue @active-route "q")
                    :p (uri-utils/getParamValue @active-route "p")
                    :type (uri-utils/getParamValue @active-route "type")}]))



