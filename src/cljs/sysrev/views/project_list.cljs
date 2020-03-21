(ns sysrev.views.project-list
  (:require [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-fx trim-v]]
            [sysrev.loading :as loading]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.create-project :refer [CreateProject]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [css]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn- ProjectListItem [{:keys [project-id name member? description]}]
  (if member?
    [:a.ui.middle.aligned.grid.segment.project-list-project
     {:href (project-uri project-id "")}
     [:div.row>div.sixteen.wide.column
      [:h4.ui.header
       [:i.grey.list.alternate.outline.icon]
       [:div.content {:display "inline-block"}
        [:span.project-title.blue-text name] description]]]]
    [:div.ui.middle.aligned.stackable.two.column.grid.segment.project-list-project.non-member
     [:div.column {:class (css [(not (util/mobile?)) "twelve wide"])}
      [:a {:href (project-uri project-id "")}
       [:h4.ui.header.blue-text
        [:i.grey.list.alternate.outline.icon]
        [:div.content
         [:span.project-title name]]]]]
     [:div.right.aligned.column {:class (css [(not (util/mobile?)) "four wide"])}
      [:div.ui.tiny.button
       {:class (css [(util/mobile?) "fluid"]
                    [(not (util/mobile?)) "blue"])
        :on-click #(dispatch [:action [:join-project project-id]])}
       "Join"]]]))

(reg-sub ::projects-list-page-num
         (fn [[_ member? id]]
           (subscribe [:view-field :projects-list [:page-num member? id]]))
         (fn [page-num] (or page-num 1)))

(reg-event-fx ::projects-list-page-num [trim-v]
              (fn [_ [member? id page-num]]
                {:dispatch [:set-view-field :projects-list
                            [:page-num member? id]
                            page-num]}))

(defn ProjectsListSegment [title projects member? & {:keys [id]}]
  (with-loader [[:identity]] {}
    (when (or (not-empty projects) (true? member?))
      (let [panel @(subscribe [:active-panel])
            items-per-page 10
            current-page @(subscribe [::projects-list-page-num member? id])
            offset (when (> (count projects) 10)
                     (* (dec current-page) items-per-page))]
        [:div.ui.segments.projects-list
         {:class (if member? "member" "non-member")
          :id (if (nil? id)
                (if member?
                  "your-projects" "available-projects")
                id)}
         (when (loading/item-loading? [:identity])
           [:div.ui.active.inverted.dimmer
            [:div.ui.loader]])
         [:div.ui.stackable.grid.segment.projects-list-header
          [:div.five.wide.column
           [:h4.ui.header title]]
          [:div.eleven.wide.right.aligned.column
           (when (> (count projects) 10)
             [ListPager
              {:panel panel
               :instance-key [:projects-list member? id]
               :offset offset
               :total-count (count projects)
               :items-per-page items-per-page
               :item-name-string "projects"
               :set-offset #(dispatch [::projects-list-page-num
                                       member? id
                                       (inc (quot % items-per-page))])
               :show-message? false}])]]
         (doall
          (->> (cond-> projects
                 offset (->> (drop offset) (take items-per-page)))
               (map (fn [{:keys [project-id] :as project}]
                      ^{:key [:projects-list title project-id]}
                      [ProjectListItem project]))))]))))

(defn FeaturedProjectsList []
  [:div.ui.segments.projects-list
   [:div.ui.segment.projects-list-header
    [:h4.ui.header "Featured Projects"]]
   [ProjectListItem {:project-id 21696
                     :name (:name @(subscribe [:public-projects 21696]))
                     :description [:span " - A managed review discovering relationships between diseases and mangiferin."]
                     :member? true}]
   [ProjectListItem {:project-id 16612
                     :name (:name @(subscribe [:public-projects 16612]))
                     :description [:span " - Winner of the sysrev mini-grants"
                                   ". This project tracks changes in insect populations."]
                     :member? true}]
   [ProjectListItem {:project-id 23706
                     :name (:name @(subscribe [:public-projects 23706]))
                     :description [:span " - A search for papers which sequenced the mitochondrial ND2 gene in island birds. "]
                     :member? true}]
   [ProjectListItem {:project-id 26314
                     :name (:name @(subscribe [:public-projects 26314]))
                     :description [:span " - A data extraction project evaluating the methods
                     used to measure \"work of breathing\" during exercise.
                     Led by Troy Cross at Mayo Clinic & University of Sydney and other researchers."]
                     :member? true}]
   [ProjectListItem {:project-id 24557
                     :name (:name @(subscribe [:public-projects 24557]))
                     :description [:span " -  aims to synthesize peer-reviewed evidence on the effects of fire on Australian invertebrates. "]
                     :member? true}]
   [ProjectListItem {:project-id 3509
                     :name (:name @(subscribe [:public-projects 3509]))
                     :description [:span " - An educational project by Dr. Lena Smirnova "
                     " at Johns Hopkins School of Public Health. Meant to teach students about zebrafish toxicology and systematic review."]
                     :member? true}]
   [ProjectListItem {:project-id 16309
                     :name (:name @(subscribe [:public-projects 16309]))
                     :description [:span " - A project rigorously evaluating published systematic reviews in the humanitarian field, specifically on conflict and war. By the "
                                   "Global Evidence Synthesis Initiative (GESI) "]
                     :member? true}]
   ])

(defn UserProjectListFull []
  (with-loader [[:identity] [:public-projects]] {}
    (when @(subscribe [:self/logged-in?])
      (let [all-projects @(subscribe [:self/projects true])
            member-projects (->> all-projects (filter :member?))
            available-projects (->> all-projects (remove :member?))]
        [:div.ui.stackable.grid
         [:div.row {:style {:padding-bottom "0"}}
          [:div.sixteen.wide.column
           [CreateProject]]]
         [:div.row
          [:div.eight.wide.column.user-projects
           [ProjectsListSegment "Your Projects" member-projects true]]
          [:div.eight.wide.column.public-projects
           [FeaturedProjectsList]
           [ProjectsListSegment "Available Projects" (reverse available-projects) false]]]]))))
