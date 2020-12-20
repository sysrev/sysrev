(ns sysrev.views.main
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.core :as data]
            [sysrev.loading :as loading]
            [sysrev.pdf :as pdf]
            [sysrev.dnd :as dnd]
            [sysrev.views.article]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.base :refer
             [panel-content logged-out-content render-panel-tree]]
            [sysrev.views.panels.login]
            [sysrev.views.panels.landing-pages.root]
            [sysrev.views.panels.landing-pages.lit-review]
            [sysrev.views.panels.landing-pages.data-extraction]
            [sysrev.views.panels.landing-pages.managed-review]
            [sysrev.views.panels.landing-pages.systematic-review]
            [sysrev.views.panels.create-org]
            [sysrev.views.panels.org.main]
            [sysrev.views.panels.org.plans]
            [sysrev.views.panels.org.payment]
            [sysrev.views.panels.org.billing]
            [sysrev.views.panels.org.projects]
            [sysrev.views.panels.org.users]
            [sysrev.views.panels.password-reset]
            [sysrev.views.panels.pubmed]
            [sysrev.views.panels.pricing]
            [sysrev.views.panels.promotion]
            [sysrev.views.panels.project.common]
            [sysrev.views.panels.project.compensation]
            [sysrev.views.panels.project.add-articles]
            [sysrev.views.panels.project.main]
            [sysrev.views.panels.project.overview]
            [sysrev.views.panels.project.articles]
            [sysrev.views.panels.project.users]
            [sysrev.views.panels.project.analytics]
            [sysrev.views.panels.project.analytics.concordance]
            [sysrev.views.panels.project.analytics.labels]
            [sysrev.views.panels.project.analytics.feedback]
            [sysrev.views.panels.project.single-article]
            [sysrev.views.panels.project.define-labels]
            [sysrev.views.panels.create-project]
            [sysrev.views.panels.project.settings]
            [sysrev.views.panels.project.export-data]
            [sysrev.views.panels.project.review]
            [sysrev.views.panels.project.support]
            [sysrev.views.panels.user.payment]
            [sysrev.views.panels.user.plans]
            [sysrev.views.panels.user.main]
            [sysrev.views.panels.user.billing]
            [sysrev.views.panels.user.compensation]
            [sysrev.views.panels.user.invitations]
            [sysrev.views.panels.user.email]
            [sysrev.views.panels.user.orgs]
            [sysrev.views.panels.user.profile]
            [sysrev.views.panels.user.projects]
            [sysrev.views.panels.user.settings]
            [sysrev.views.panels.user.verify-email]
            [sysrev.views.panels.users]
            [sysrev.views.panels.terms-of-use]
            [sysrev.views.menu :refer [header-menu]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.review :as review]
            [sysrev.views.panels.search]
            [sysrev.util :as util :refer [css]]
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

(defn SidebarAnnotationMenu []
  (r/create-class
   {:component-did-mount #(util/update-sidebar-height)
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

(defn SidebarColumn []
  (let [article-id @(subscribe [:visible-article-id])
        article-datasource @(subscribe [:article/datasource-name article-id])
        editing-id @(subscribe [:review/editing-id])
        interface @(subscribe [:review-interface])]
    (when (review/display-sidebar?)
      [:div.column.panel-side-column
       ;; keep sidebar width as 3 in test suite for now
       ;; (changing breaks annotation test positions)
       {:class (css "four" "wide")}
       [ui/WrapFixedVisibility 10
        [:div.review-menu
         [ui/tabbed-panel-menu
          [{:tab-id :labels
            :content "Labels"
            :action #(dispatch [:set-review-interface :labels])
            :disabled (nil? editing-id)}
           {:tab-id :annotations
            :content "Annotations"
            :action #(dispatch [:set-review-interface :annotations])
            :disabled (or (= "ctgov" article-datasource)
                          (= "entity" article-datasource))}]
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
  (let [mobile? (util/mobile?)
        social-text? (not mobile?) #_ false
        sysrev-links
        [:span.links
         [:a {:target "_blank" :href "https://blog.sysrev.com"} "Blog"]
         [:a {:target "_blank" :href "https://twitter.com/sysrev1"}
          [:i.twitter.icon] (when social-text? "Twitter")]
         [:a {:target "_blank" :href "https://www.linkedin.com/company/sysrev"}
          [:i.linkedin.icon] (when social-text? "LinkedIn")]
         [:a {:target "_blank" :href "https://www.facebook.com/insilica/"}
          [:i.facebook.icon] (when social-text? "Facebook")]
         #_ [:a {:target "_blank" :href "https://www.reddit.com/r/sysrev"}
             [:i.reddit.alien.icon] "Reddit"]]
        contact-email
        [:span.email "info@insilica.co"]
        copyright-notice
        [:span [:span.medium-weight "Sysrev "] (str "© " (-> (js/Date.) (.getFullYear)) " Insilica LLC")]
        site-terms [:a#terms-link {:href "/terms-of-use"} "Terms of Use"]]
    [:div#footer
     (if (util/mobile?)
       [:div.ui.container>div.ui.middle.aligned.grid
        [:div.left.aligned.six.wide.column contact-email]
        [:div.right.aligned.ten.wide.column
         [:div.wrapper sysrev-links " | " site-terms]]]
       [:div.ui.container>div.ui.middle.aligned.stackable.grid
        [:div.left.aligned.six.wide.column copyright-notice]
        [:div.right.aligned.ten.wide.column
         [:div.wrapper contact-email sysrev-links "|" site-terms]]])]))

(defn main-content []
  (let [landing? @(subscribe [:landing-page?])]
    (if-not @(subscribe [:initialized?])
      (loading-content)
      [dnd/wrap-dnd-app
       [:div#toplevel {:class (css [landing? "landing"])}
        [:div#main-content {:class (css [(review/display-sidebar?) "annotator"]
                                        [landing? "landing"]
                                        [(or (not @(subscribe [:data/ready?]))
                                             (data/loading?
                                              nil :ignore (into loading/ignore-data-names
                                                                #{:pdf/open-access-available?
                                                                  :pdf/article-pdfs})))
                                         "loading"])}
         [header-menu]
         [:div.panel-content {:class (css [(not landing?) "ui container"])}
          (if (review/display-sidebar?)
            [:div.ui.grid
             [SidebarColumn]
             [:div.column
              {:class (css "twelve" "wide")}
              [active-panel-content]]]
            [active-panel-content])]]
        [GlobalFooter]]])))
