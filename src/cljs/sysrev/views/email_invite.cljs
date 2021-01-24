(ns sysrev.views.email-invite
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe dispatch dispatch-sync
                                   reg-sub reg-event-db]]
            [sysrev.state.ui :refer [set-panel-field]]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.views.semantic :as S :refer [Button]]
            [sysrev.util :as util :refer [format]]))

(defn text->emails [text]
  (when (string? text)
    (->> (str/split text #"[ ,\n]")
         (map str/trim)
         (filterv util/email?))))

(reg-sub ::emails-text
         :<- [:panel-field [:invite-emails :emails-text]]
         identity)

(reg-event-db ::emails-text
              (fn [db [_ text]]
                (set-panel-field db [:invite-emails :emails-text] text)))

(reg-sub ::emails
         :<- [::emails-text]
         (fn [text] (text->emails text)))

(def-action :project/send-invites
  :uri      "/api/send-project-invites"
  :content  (fn [project-id emails]
              {:project-id project-id :emails emails})
  :process  (fn [_ _ {:keys [success message] :as result}]
              (if success
                {:dispatch-n [[::emails-text ""]
                              [:toast {:class "success" :message message}]]}
                (util/log-err "error in :project/send-invites: %s" (pr-str result))))
  :on-error (fn [{:keys [db error]} _ _]
              {:dispatch [:toast {:class "error" :message (:message error)}]}))

(defn InviteEmails []
  (let [project-id @(subscribe [:active-project-id])
        emails-text @(subscribe [::emails-text])
        emails @(subscribe [::emails])
        email-count (count emails)
        unique-count (count (set emails))
        running? (action/running? :project/send-invites)]
    [:form.ui.stackable.form.bulk-invites-form
     {:on-submit (util/wrap-prevent-default
                  #(dispatch [:action [:project/send-invites project-id emails]]))}
     [:div.sixteen.wide.field>div.ui.input
      [:textarea#bulk-invite-emails
       {:rows 5
        :value emails-text
        :required true
        :placeholder "Input a list of emails separated by comma, newlines or spaces."
        :on-change (util/wrap-prevent-default
                    (util/on-event-value #(dispatch-sync [::emails-text %])))}]]
     [:div.fields {:style {:margin-bottom 0}}
      [:div.eight.wide.field
       [Button {:id "send-bulk-invites-button"
                :type "submit" :primary true
                :disabled (or running? (zero? unique-count))}
        "Send Invites"]]
      (cond (pos? email-count)
            [:div.eight.wide.field {:style {:text-align "right"}}
             [:div.ui.large.basic.green.label.emails-status
              [:i.check.circle.icon]
              [:span.black-text
               (util/pluralized-count email-count "email")
               (when (> email-count unique-count)
                 (format " (%d unique)" unique-count))]]]
            (and (some-> emails-text str/trim not-empty)
                 (zero? email-count))
            [:div.eight.wide.field {:style {:text-align "right"}}
             [:div.ui.large.basic.label.emails-status
              [:span.black-text "0 emails"]]])]]))
