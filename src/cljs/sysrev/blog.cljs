(ns sysrev.blog
  (:require [cljs-time.format :as tf]
            [cljs-time.coerce :as tc]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.nav :as nav]
            [sysrev.state.nav :refer [set-subpanel-default-uri]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.markdown :refer [RenderMarkdown]]
            [sysrev.views.menu :refer [loading-indicator]]
            [sysrev.util :as util]
            [sysrev.macros :refer-macros [defroute-app-id with-loader]]))

(defn init-blog []
  (dispatch [:blog/load-default-panels])
  (dispatch [:require [:blog/entries]]))

(defn main-site-url []
  (let [[_ main-host] (re-matches #"blog\.(.*)$" js/window.location.host)]
    (if (empty? main-host)
      "https://sysrev.com/"
      (str js/window.location.protocol
           "//"
           main-host))))

(defn blog-header-menu []
  [:div.ui.menu.site-menu
   [:div.ui.container
    [:a.header.item {:href (main-site-url)}
     [:img.ui.middle.aligned.image
      (-> {:src "/SysRev_header_2.png" :alt "SysRev"}
          (merge
           (if (util/mobile?)
             {:width "80" :height "25"}
             {:width "90" :height "28"})))]]
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

(reg-sub :blog/entries #(get-in % [:data :blog :entries]))

(reg-sub :blog/active-entry #(get-in % [:state :blog :active-entry]))

(reg-event-db :blog/active-entry [trim-v]
              (fn [db [filename]]
                (assoc-in db [:state :blog :active-entry] filename)))

(declare blog-root blog-entry)

(defroute-app-id blog-root "/" [] :blog
  (dispatch [:set-active-panel [:blog :list]])
  (dispatch [:blog/active-entry nil])
  (dispatch [:reload [:blog/entries]])
  (set! (-> js/document .-title)
        "Sysrev Blog"))

(defroute-app-id blog-entry "/posts/:filename" [filename] :blog
  (if (empty? filename)
    (nav/nav-redirect "/")
    (do (dispatch [:set-active-panel [:blog :entry]])
        (dispatch [:blog/active-entry filename]))))

(defn filename-from-url [url]
  (or (second (re-matches #".*sysrev-blog/(.*).html$" url))
      (second (re-matches #".*sysrev-blog/(.*)$" url))))

(defn BlogListEntry [{:keys [blog-entry-id url title description date-published]}]
  [:div.ui.secondary.segment.blog-list-entry
   [:h5.entry-date
    (tf/unparse (tf/formatters :date)
                (tc/to-date-time date-published))]
   [:h3.entry-title
    [:a {:href (str "/posts/" (filename-from-url url))} title]]
   (when (not-empty description)
     [RenderMarkdown description])])

(defn BlogEntryContent [{:keys [blog-entry-id url title description date-published]}]
  (set! (-> js/document .-title)
        (str "Sysrev Blog - " title))
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
  (fn [_child]
    [:div
     [:a.ui.large.fluid.labeled.icon.button {:href "/"}
      [:i.left.arrow.icon]
      "All Blog Entries"]
     (with-loader [[:blog/entries]] {}
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
            [BlogEntryContent entry]))])]))

(defn- load-default-panels [db]
  (->> [[[:blog :list]
         "/"]

        [[:blog :entry]
         "/posts"]]
       (reduce (fn [db [prefix uri]]
                 (set-subpanel-default-uri db prefix uri))
               db)))

(reg-event-db :blog/load-default-panels load-default-panels)
