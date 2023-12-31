(ns sysrev.views.project-list
  (:require [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-fx trim-v]]
            [sysrev.data.core :as data]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.panels.create-project :refer [NewProjectButton]]
            [sysrev.views.project :refer [ProjectName]]
            [sysrev.util :as util :refer [css]]
            [sysrev.macros :refer-macros [with-loader]]))

(defn- ProjectListItem [{:keys [project-id name member? description public-access project-owner]}]
  (let [owner-id (:user-id project-owner)
        owner-name (if owner-id
                     @(subscribe [:user/username owner-id])
                     (:name project-owner))
        {:keys [invite-code]} @(subscribe [:project/raw project-id])]
    (if member?
      [:a.ui.middle.aligned.grid.segment.project-list-project
       {:href (project-uri project-id "")}
       [:div.row>div.sixteen.wide.column
        [:h4.ui.header
         (if public-access
           [:i.grey.list.alternate.outline.icon]
           [:i.grey.lock.icon])
         [:div.content {:display "inline-block"}
          [:span.project-title.blue-text [ProjectName name owner-name]]
          description]]]]
      [:div.ui.middle.aligned.stackable.two.column.grid.segment.project-list-project.non-member
       [:div.column {:class (css [(not (util/mobile?)) "twelve wide"])}
        [:a {:href (project-uri project-id "")}
         [:h4.ui.header.blue-text
          (if (nil?  public-access)
            [:i.grey.list.alternate.outline.icon]
            (if public-access
              [:i.grey.list.alternate.outline.icon]
              [:i.grey.lock.icon]))
          [:div.content
           [:span.project-title [ProjectName name owner-name]]]]]]
       [:div.right.aligned.column {:class (css [(not (util/mobile?)) "four wide"])}
        [:div.ui.tiny.button
         {:class (css [(util/mobile?) "fluid"]
                      [(not (util/mobile?)) "blue"])
          :on-click #(dispatch [:action [:join-project invite-code]])}
         "Join"]]])))

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
         (when (data/loading? [:identity])
           [:div.ui.active.inverted.dimmer
            [:div.ui.loader]])
         [:div.ui.stackable.grid.segment.projects-list-header
          [:div.five.wide.column
           [:h4.ui.header title]]
          (when (= title "Your Projects")
            [:div.eleven.wide.right.aligned.column
             {:style {:padding "0.5rem"}}
             [NewProjectButton]])]
         (doall
          (->> (cond-> projects
                 offset (->> (drop offset) (take items-per-page)))
               (map (fn [{:keys [project-id] :as project}]
                      ^{:key [:projects-list title project-id]}
                      [ProjectListItem project]))))
         (when (> (count projects) 10)
           [:div.ui.stackable.grid.segment.projects-list-header
            [:div.sixteen.wide.aligned.column
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
                 :show-message? false}])]])
         (when-not (seq projects)
           [:div.ui.stackable.grid.segment.projects-list-header
            [:div.sixteen.wide.aligned.column
             [:h4 "You don't have any projects yet, create a new one to get started with sysrev!"]]])]))))

;; note you need to manually add the :project-owner and :public-access true
(defn FeaturedProjectsList []
  [:div.ui.segments.projects-list
   [:div.ui.segment.projects-list-header
    [:h4.ui.header "Featured Projects"]]
   (let [public @(subscribe [:public-projects])
         make-item (fn [{:keys [project-id description owner]}]
                     {:project-id project-id
                      :name (get-in public [project-id :name])
                      :description [:span (str " - " description)]
                      :project-owner (or owner (get-in public [project-id :owner]))
                      :public-access true
                      :member? true})]
     (doall
      (for [x [{:project-id 21696
                :description "A managed review discovering relationships between diseases and mangiferin."
                :owner {:name "Insilica"}}
               {:project-id 16612
                :description "Winner of the sysrev mini-grants. This project tracks changes in insect populations."}
               {:project-id 23706
                :description "A search for papers which sequenced the mitochondrial ND2 gene in island birds."}
               {:project-id 27698
                :description "A data extraction project evaluating the methods used to measure \"work of breathing\" during exercise. Led by Troy Cross at Mayo Clinic & University of Sydney and other researchers."}
               {:project-id 24557
                :description "This project was established in response to the catastrophic 2019-20 bushfire season in Australia, and will identify key knowledge gaps and research priorities for understanding how Australian invertebrates respond to fire events."}
               {:project-id 3509
                :description "An educational project by Dr. Lena Smirnova at Johns Hopkins School of Public Health. Meant to teach students about zebrafish toxicology and systematic review."}
               {:project-id 16309
                :description "A project rigorously evaluating published systematic reviews in the humanitarian field, specifically on conflict and war. By the Global Evidence Synthesis Initiative (GESI)."}]]
        ^{:key (:project-id x)}
        [ProjectListItem (make-item x)])))])

(defn UserProjectListFull []
  (with-loader [[:identity] [:public-projects]] {}
    (when @(subscribe [:self/logged-in?])
      (let [all-projects @(subscribe [:self/projects true])
            member-projects (->> all-projects (filter :member?))]
        [:div.ui.stackable.grid
         [:div.row
          [:div.eight.wide.column.user-projects
           [ProjectsListSegment "Your Projects" member-projects true]]
          [:div.eight.wide.column.public-projects
           [FeaturedProjectsList]]]]))))
