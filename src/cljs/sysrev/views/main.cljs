(ns sysrev.views.main
  (:require [cljsjs.jquery]
            [cljsjs.semantic-ui]
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db]]
            [re-frame.db :refer [app-db]]
            [sysrev.loading :as loading]
            [sysrev.pdf]
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

(defn main-content []
  (if @(subscribe [:initialized?])
    (let [project-id @(subscribe [:active-project-id])
          article-id @(subscribe [:visible-article-id])
          ann-context {:class "abstract"
                       :project-id project-id
                       :article-id article-id}
          annotator?
          (and project-id article-id
               @(subscribe [:annotator/enabled ann-context])
               (util/annotator-size?))]
      [:div.main-content
       {:class (cond-> ""
                 (or (not @(subscribe [:data/ready?]))
                     (loading/any-loading?
                      :ignore (into loading/ignore-data-names
                                    [:pdf/open-access-available?
                                     :pdf/article-pdfs])))
                 (str " loading")
                 annotator?
                 (str " annotator"))}
       [header-menu]
       [:div.ui.container.panel-content
        (if annotator?
          [:div.ui.grid
           [:div.three.wide.column.panel-side-column
            [ui/WrapFixedVisibility 10
             [annotator/AnnotationMenu ann-context "abstract"]]]
           [:div.thirteen.wide.column
            [active-panel-content]]]
          [active-panel-content])]
       [notifier @(subscribe [:active-notification])]])
    loading-content))
