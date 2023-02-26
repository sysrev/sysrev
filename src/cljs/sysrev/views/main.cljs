(ns sysrev.views.main
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [sysrev.dnd :as dnd]
            [sysrev.pdf :as pdf]
            [sysrev.shared.components :refer [loading-content]]
            [sysrev.util :as util :refer [css]]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.article]
            [sysrev.views.base :refer
             [logged-out-content panel-content
              render-panel-tree]]
            [sysrev.views.components.core :as ui]
            [sysrev.views.menu :refer [header-menu]]
            [sysrev.views.panels.create-org]
            [sysrev.views.panels.create-project]
            [sysrev.views.panels.landing-pages.data-extraction]
            [sysrev.views.panels.landing-pages.lit-review]
            [sysrev.views.panels.landing-pages.managed-review]
            [sysrev.views.panels.landing-pages.root]
            [sysrev.views.panels.landing-pages.systematic-review]
            [sysrev.views.panels.login]
            [sysrev.views.panels.org.billing]
            [sysrev.views.panels.org.main]
            [sysrev.views.panels.org.payment]
            [sysrev.views.panels.org.plans]
            [sysrev.views.panels.org.projects]
            [sysrev.views.panels.org.users]
            [sysrev.views.panels.password-reset]
            [sysrev.views.panels.pricing]
            [sysrev.views.panels.project.add-articles]
            [sysrev.views.panels.project.analytics]
            [sysrev.views.panels.project.analytics.concordance]
            [sysrev.views.panels.project.analytics.feedback]
            [sysrev.views.panels.project.analytics.labels]
            [sysrev.views.panels.project.articles]
            [sysrev.views.panels.project.articles-data]
            [sysrev.views.panels.project.common]
            [sysrev.views.panels.project.compensation]
            [sysrev.views.panels.project.define-labels]
            [sysrev.views.panels.project.export-data]
            [sysrev.views.panels.project.main]
            [sysrev.views.panels.project.overview]
            [sysrev.views.panels.project.review]
            [sysrev.views.panels.project.settings]
            [sysrev.views.panels.project.single-article]
            [sysrev.views.panels.project.support]
            [sysrev.views.panels.project.users]
            [sysrev.views.panels.promotion]
            [sysrev.views.panels.pubmed]
            [sysrev.views.panels.search]
            [sysrev.views.panels.terms-of-use]
            [sysrev.views.panels.user.billing]
            [sysrev.views.panels.user.compensation]
            [sysrev.views.panels.user.email]
            [sysrev.views.panels.user.invitations]
            [sysrev.views.panels.user.main]
            [sysrev.views.panels.user.orgs]
            [sysrev.views.panels.user.payment]
            [sysrev.views.panels.user.plans]
            [sysrev.views.panels.user.profile]
            [sysrev.views.panels.user.projects]
            [sysrev.views.panels.user.settings]
            [sysrev.views.panels.user.verify-email]
            [sysrev.views.panels.users]
            [sysrev.views.review :as review]
            [sysrev.views.semantic :as S]
            [sysrev.base :as base]))

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
   {:component-did-mount util/update-sidebar-height
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

(defn SidebarColumn [!ref]
  (let [article-id @(subscribe [:visible-article-id])
        editing-id @(subscribe [:review/editing-id])
        interface @(subscribe [:review-interface])]
    (when (review/display-sidebar?)
      [:div.column.panel-side-column {:class "four wide"}
       [S/Sticky {:context @!ref :offset 10}
        [:div.review-menu
         [ui/tabbed-panel-menu
          [{:tab-id :labels
            :content (if (not= interface :labels)
                       [:span [:i.arrow.left.icon] " Back to Labels"]
                       "Labels")
            :action #(dispatch [:set-review-interface :labels])
            :disabled (nil? editing-id)}]
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
        social-text? (not mobile?) #_false
        sysrev-links
        [:span.links
         (when @base/show-blog-links
           [:a {:target "_blank" :href "https://blog.sysrev.com"} "Blog"])
         [:a {:target "_blank" :href "https://twitter.com/sysrev1"}
          [:i.twitter.icon] (when social-text? "Twitter")]
         [:a {:target "_blank" :href "https://www.linkedin.com/company/sysrev"}
          [:i.linkedin.icon] (when social-text? "LinkedIn")]
         [:a {:target "_blank" :href "https://www.facebook.com/insilica/"}
          [:i.facebook.icon] (when social-text? "Facebook")]
         #_[:a {:target "_blank" :href "https://www.reddit.com/r/sysrev"}
            [:i.reddit.alien.icon] "Reddit"]]
        contact-email
        [:span.email "info@insilica.co"]
        copyright-notice
        [:span [:span.medium-weight "Sysrev "] (str "© " (-> (js/Date.) (.getFullYear)) " Insilica LLC")]
        site-terms [:a#terms-link {:href "/terms-of-use"} "Terms"]
        citation-link [:a {:href (if @base/show-blog-links
                                   "https://blog.sysrev.com/how-to-cite"
                                   "https://www.frontiersin.org/articles/10.3389/frai.2021.685298/full")
                           :target "_blank"}
                       "Cite"]]
    [:div#footer
     (if (util/mobile?)
       [:div.ui.container>div.ui.middle.aligned.grid
        [:div.left.aligned.six.wide.column contact-email]
        [:div.right.aligned.ten.wide.column
         [:div.wrapper sysrev-links " | " site-terms " | " citation-link]]]
       [:div.ui.container>div.ui.middle.aligned.stackable.grid
        [:div.left.aligned.six.wide.column copyright-notice]
        [:div.right.aligned.ten.wide.column
         [:div.wrapper contact-email sysrev-links "|" site-terms " | " citation-link]]])]))

(defn main-content []
  (let [!ref (atom nil)]
    (fn []
      (let [landing? @(subscribe [:landing-page?])]
        (if-not @(subscribe [:initialized?])
          (loading-content)
          [dnd/wrap-dnd-app
           [:div#toplevel {:class (css [landing? "landing"])
                           :ref #(reset! !ref %)}
            [:div#main-content {:class (css [(review/display-sidebar?) "annotator"]
                                            [landing? "landing"])}
             [header-menu]
             [:div.panel-content {:class (css [(not landing?) "ui container"])}
              (if (review/display-sidebar?)
                [S/Ref {:innerRef @!ref}
                 [:div.ui.grid
                  [SidebarColumn !ref]
                  [:div.twelve.wide.column
                   [active-panel-content]]]]
                [active-panel-content])]]
            [ui/AlertMessageContainer]
            [GlobalFooter]]])))))
