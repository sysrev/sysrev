(ns sysrev.blog
  (:require [cljs-time.core :as t]
            [cljs-time.format :as tf]
            [cljs-time.coerce :as tc]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-db trim-v]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [set-subpanel-default-uri]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.routes :as routes]
            [sysrev.markdown :refer [RenderMarkdown]]
            [sysrev.views.menu :refer [loading-indicator]]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.shared.util :refer [parse-integer]]
            [sysrev.macros])
  (:require-macros [secretary.core :refer [defroute]]
                   [sysrev.macros :refer [defroute-app-id with-loader]]))

(defn init-blog []
  (dispatch [:blog/load-default-panels])
  (dispatch [:fetch [:blog/entries]]))

(defn blog-header-menu []
  [:div.ui.menu.site-menu
   [:div.ui.container
    [:a.header.item {:href "https://sysrev.com/"}
     [:img.ui.middle.aligned.image
      {:src "/SysRev_header.png" :alt "SysRev"
       :width "65" :height "20"}]]
    [:a.item {:href "/"} "Blog"]
    [loading-indicator]
    [:div.right.menu
     [:div.item {:style {:width "0" :padding "0"}}]]]])

(def-data :blog/entries
  :loaded? (fn [db] (-> (get-in db [:data :blog])
                        (contains? :entries)))
  :uri (fn [] "/api/blog-entries")
  :prereqs (fn [] [])
  :process (fn [{:keys [db]} [] {:keys [entries]}]
             {:db (assoc-in db [:data :blog :entries] entries)}))

(reg-sub
 :blog/entries
 (fn [db [_]] (get-in db [:data :blog :entries])))

(reg-sub
 :blog/active-entry
 (fn [db [_]] (get-in db [:state :blog :active-entry])))

(reg-event-db
 :blog/active-entry
 [trim-v]
 (fn [db [filename]] (assoc-in db [:state :blog :active-entry] filename)))

(defroute-app-id blog-root "/" [] :blog
  (dispatch [:set-active-panel [:blog :list]])
  (dispatch [:blog/active-entry nil])
  (set! (-> js/document .-title)
        "SysRev Blog"))

(defroute-app-id blog-entry "/posts/:filename" [filename] :blog
  (if (empty? filename)
    (nav/nav-redirect "/")
    (do (dispatch [:set-active-panel [:blog :entry]])
        (dispatch [:blog/active-entry filename]))))

(defn filename-from-url [url]
  (or (second (re-matches #".*sysrev-blog/(.*).html$" url))
      (second (re-matches #".*sysrev-blog/(.*)$" url))))

(defn BlogListEntry
  [{:keys [blog-entry-id url title description date-published]
    :as entry}]
  [:div.ui.secondary.segment.blog-list-entry
   [:h5.entry-date
    (tf/unparse (tf/formatters :date)
                (tc/to-date-time date-published))]
   [:h3.entry-title
    [:a {:href (str "/posts/" (filename-from-url url))} title]]
   (when (not-empty description)
     [RenderMarkdown description])])

(defn BlogEntryContent
  [{:keys [blog-entry-id url title description date-published]
    :as entry}]
  (set! (-> js/document .-title)
        (str "SysRev Blog - " title))
  [:div
   [:iframe {:id (str "blog-entry-" blog-entry-id)
             :title title
             :src url
             :width "100%"
             :frameBorder "0"}]])

(defmethod panel-content [:blog] []
  (fn [child] [:div.blog-wrapper child]))

(defmethod panel-content [:blog :list] []
  (fn [child]
    [:div.ui.segment.blog-entries
     [:h5.ui.small.dividing.header "Blog Entries"]
     (with-loader [[:blog/entries]] {}
       (let [entries @(subscribe [:blog/entries])]
         [:div
          (doall
           (for [entry entries]
             ^{:key [:blog-entry (:blog-entry-id entry)]}
             [BlogListEntry entry]))]))
     child]))

(defmethod panel-content [:blog :entry] []
  (fn [child]
    (with-loader [[:blog/entries]] {}
      [:div
       [:a.ui.large.fluid.labeled.icon.button {:href "/"}
        [:i.left.arrow.icon]
        "All Blog Entries"]
       [:div.blog-entry
        (let [entries @(subscribe [:blog/entries])
              active-filename @(subscribe [:blog/active-entry])]
          (when-let [entry (->> entries
                                (filter #(= (-> % :url filename-from-url)
                                            active-filename))
                                first)]
            (js/setTimeout
             (fn []
               (let [el (js/$ (str "#blog-entry-" (:blog-entry-id entry)))]
                 (-> el
                     (.on
                      "load"
                      (fn []
                        (let [doc (js/$ (-> el (.get 0) .-contentWindow .-document))
                              height (some-> doc (.height) (str "px"))]
                          (when height
                            (-> el (.css "height" height))
                            (-> el (.css "overflow" "hidden"))
                            (-> el (.attr "scrolling" "no")))))))))
             25)
            [BlogEntryContent entry]))]])))

(defn- load-default-panels [db]
  (->> [[[]
         "/"]

        [[:blog :list]
         "/"]

        [[:blog :entry]
         "/entry"]]
       (reduce (fn [db [prefix uri]]
                 (set-subpanel-default-uri db prefix uri))
               db)))

(reg-event-db :blog/load-default-panels load-default-panels)
