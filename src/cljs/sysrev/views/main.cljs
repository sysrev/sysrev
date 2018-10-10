(ns sysrev.views.main
  (:require [cljsjs.jquery]
            [cljsjs.semantic-ui]
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.loading :as loading]
            [sysrev.pdf :as pdf]
            [sysrev.views.article]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.base :refer
             [panel-content logged-out-content render-panel-tree]]
            [sysrev.views.panels.create-project]
            [sysrev.views.panels.login :refer [LoginRegisterPanel]]
            [sysrev.views.panels.root]
            [sysrev.views.panels.password-reset]
            [sysrev.views.panels.payment]
            [sysrev.views.panels.pubmed]
            [sysrev.views.panels.plans]
            [sysrev.views.panels.select-project]
            [sysrev.views.panels.user-settings]
            [sysrev.views.panels.project.common]
            [sysrev.views.panels.project.compensation]
            [sysrev.views.panels.project.add-articles]
            [sysrev.views.panels.project.main]
            [sysrev.views.panels.project.overview]
            [sysrev.views.panels.project.articles]
            [sysrev.views.panels.project.public-labels-old]
            [sysrev.views.panels.project.user-labels-old]
            [sysrev.views.panels.project.define-labels]
            [sysrev.views.panels.project.settings]
            [sysrev.views.panels.project.invite-link]
            [sysrev.views.panels.project.export-data]
            [sysrev.views.panels.project.review]
            [sysrev.views.panels.project.support]
            [sysrev.views.menu :refer [header-menu]]
            [sysrev.views.components :as ui]
            [sysrev.views.review :as review :refer [LabelsColumns]]
            [sysrev.util :as util]
            [sysrev.shared.components :refer [loading-content]]))

(defmethod panel-content :default []
  (fn [child]
    [:div
     [:h2 "route not found"]
     child]))

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

(reg-event-db
 :set-review-interface
 [trim-v]
 (fn [db [interface]]
   (assoc-in db [:state :review-interface] interface)))

(reg-sub
 :review-interface
 (fn [db]
   (or
    (get-in db [:state :review-interface])
    :labels)))

(defn main-content []
  (if @(subscribe [:initialized?])
    (let [project-id @(subscribe [:active-project-id])
          article-id @(subscribe [:visible-article-id])
          pdf-url (when article-id
                    @(subscribe [:view-field :article [article-id :visible-pdf]]))
          review-interface @(subscribe [:review-interface])
          pdf-key (some-> pdf-url pdf/pdf-url->key)
          ann-context (if pdf-key
                        {:class "pdf"
                         :project-id project-id
                         :article-id article-id
                         :pdf-key pdf-key}
                        {:class "abstract"
                         :project-id project-id
                         :article-id article-id})]
      [:div.main-content
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
           [:div.three.wide.column.panel-side-column
            [ui/WrapFixedVisibility 10
             [:div
              [ui/tabbed-panel-menu
               [{:tab-id :labels
                 :content "Labels"
                 :action #(dispatch [:set-review-interface :labels])}
                {:tab-id :annotations
                 :content "Annotations"
                 :action #(dispatch [:set-review-interface :annotations])}]
               review-interface
               "review-interface"]
              (if (= review-interface :labels)
                [:div
                 [LabelsColumns article-id 1]
                 [review/SaveButton article-id]
                 [review/SkipArticle article-id]]
                [annotator/AnnotationMenu ann-context "abstract"])]]]
           [:div.thirteen.wide.column
            [active-panel-content]]]
          [active-panel-content])]
       [notifier @(subscribe [:active-notification])]])
    loading-content))
