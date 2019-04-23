(ns sysrev.views.main
  (:require [cljsjs.jquery]
            [cljsjs.semantic-ui]
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.loading :as loading]
            [sysrev.pdf :as pdf]
            [sysrev.blog :as blog]
            [sysrev.views.article]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.base :refer
             [panel-content logged-out-content render-panel-tree]]
            [sysrev.views.article-list.core :as alist]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.root]
            [sysrev.views.panels.org.main]
            [sysrev.views.panels.org.plans]
            [sysrev.views.panels.password-reset]
            [sysrev.views.panels.pubmed]
            [sysrev.views.panels.project.common]
            [sysrev.views.panels.project.compensation]
            [sysrev.views.panels.project.add-articles]
            [sysrev.views.panels.project.main]
            [sysrev.views.panels.project.overview]
            [sysrev.views.panels.project.articles :as project-articles]
            [sysrev.views.panels.project.single-article :as single-article]
            [sysrev.views.panels.project.define-labels]
            [sysrev.views.panels.project.settings]
            [sysrev.views.panels.project.invite-link]
            [sysrev.views.panels.project.export-data]
            [sysrev.views.panels.project.review]
            [sysrev.views.panels.project.support]
            [sysrev.views.panels.user.main]
            [sysrev.views.panels.user.compensation]
            [sysrev.views.panels.user.payment]
            [sysrev.views.panels.user.plans]
            [sysrev.views.panels.users]
            [sysrev.views.menu :refer [header-menu]]
            [sysrev.views.components :as ui]
            [sysrev.views.review :as review]
            [sysrev.util :as util]
            [sysrev.shared.components :refer [loading-content]]))

(defmethod panel-content :default []
  (fn [child]
    [:div [:h2 "route not found"]
     child]))

(defmethod logged-out-content :logged-out []
  [:div [:h2 "You must be logged in to perform this action"]
   [:a {:href "/login"} "Log In"]])

(defmethod logged-out-content :default []
  nil)

(defn active-panel-content []
  (let [active-panel @(subscribe [:active-panel])
        have-logged-out-content?
        ((comp not nil?) (logged-out-content active-panel))
        logged-in? @(subscribe [:self/logged-in?])]
    (if (or logged-in? (not have-logged-out-content?))
      [render-panel-tree active-panel]
      [:div#logged-out
       [logged-out-content active-panel]])))

(defn notifier [entry]
  [:div])

(defn SidebarAnnotationMenu []
  (r/create-class
   {:component-did-mount (fn [] (util/update-sidebar-height))
    :reagent-render
    (fn []
      (let [article-id @(subscribe [:visible-article-id])
            pdf-key (when article-id
                      (some-> @(subscribe [:view-field :article [article-id :pdf-url]])
                              pdf/pdf-url->key))
            ann-context (cond-> {:project-id @(subscribe [:active-project-id])
                                 :article-id article-id}
                          pdf-key         (merge {:class "pdf" :pdf-key pdf-key})
                          (nil? pdf-key)  (merge {:class "abstract"}))]
        [annotator/AnnotationMenu ann-context "abstract"]))}))

(defn get-article-list-context []
  (let [panel @(subscribe [:active-panel])]
    (cond (= panel project-articles/panel)  (project-articles/get-context)
          (= panel single-article/panel)    (single-article/get-context))))

(defn SidebarColumn []
  (let [article-id @(subscribe [:visible-article-id])
        editing-id @(subscribe [:review/editing-id])
        interface @(subscribe [:review-interface])]
    (when (review/display-sidebar?)
      [:div.three.wide.column.panel-side-column
       [ui/WrapFixedVisibility 10
        [:div.review-menu
         [ui/tabbed-panel-menu
          [{:tab-id :labels
            :content "Labels"
            :action #(dispatch [:set-review-interface :labels])
            :disabled (nil? editing-id)}
           {:tab-id :annotations
            :content "Annotations"
            :action #(dispatch [:set-review-interface :annotations])}]
          interface
          "review-interface"]
         (case interface
           :labels       [review/LabelAnswerEditorColumn article-id]
           :annotations  [SidebarAnnotationMenu]
           nil)
         (when (or @(subscribe [:review/on-review-task?])
                   (= interface :labels))
           [review/SaveSkipColumnSegment article-id])]]])))

(defn GlobalFooter []
  (let [sysrev-links
        [:span.links
         [:a {:target "_blank" :href "https://blog.sysrev.com"} "Blog"]
         [:a {:target "_blank" :href "https://twitter.com/sysrev1"}
          [:i.twitter.icon] "Twitter"]
         [:a {:target "_blank" :href "https://www.linkedin.com/company/sysrev"}
          [:i.linkedin.icon] "LinkedIn"]
         [:a {:target "_blank" :href "https://www.facebook.com/insilica/"}
          [:i.facebook.icon] "Facebook"]
         #_ [:a {:target "_blank" :href "https://www.reddit.com/r/sysrev"}
             [:i.reddit.alien.icon] "Reddit"]]
        contact-email
        [:span.email "info@insilica.co"]
        copyright-notice
        [:span [:span.medium-weight "Sysrev "] "© 2019 Insilica LLC"]]
    [:div#footer
     (if (util/mobile?)
       [:div.ui.container
        [:div.ui.middle.aligned.grid
         [:div.left.aligned.four.wide.column contact-email]
         [:div.right.aligned.twelve.wide.column
          [:div.wrapper sysrev-links]]]]
       [:div.ui.container.middle.aligned.stackable.grid
        [:div.left.aligned.four.wide.column copyright-notice]
        [:div.right.aligned.twelve.wide.column
         [:div.wrapper contact-email sysrev-links]]])]))

(defn main-content []
  (if-not @(subscribe [:initialized?])
    (loading-content)
    (case @(subscribe [:app-id])
      :blog
      [:div#toplevel
       [:div#main-content
        [blog/blog-header-menu]
        [:div.ui.container.blog-content
         [active-panel-content]]]
       [GlobalFooter]]
      [:div#toplevel
       [:div#main-content
        {:class (cond-> ""
                  (or (not @(subscribe [:data/ready?]))
                      (loading/any-loading?
                       :ignore (into loading/ignore-data-names
                                     [:pdf/open-access-available?
                                      :pdf/article-pdfs])))
                  (str " loading")
                  (review/display-sidebar?)
                  (str " annotator"))}
        [header-menu]
        [:div.ui.container.panel-content
         (if (review/display-sidebar?)
           [:div.ui.grid
            [SidebarColumn]
            [:div.thirteen.wide.column
             [active-panel-content]]]
           [active-panel-content])]]
       [notifier @(subscribe [:active-notification])]
       [GlobalFooter]])))
