(ns sysrev.state.review
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [re-frame.core :refer
             [subscribe reg-sub reg-sub-raw reg-event-db reg-event-fx trim-v]]
            [reagent.ratom :refer [reaction]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.nav :refer [active-panel active-project-id]]
            [sysrev.state.article :as article]
            [sysrev.util :as util :refer [in? map-values]]))

(defn- review-task-state [db & [project-id]]
  (get-in db [:data :review (or project-id (active-project-id db))]))

(defn review-task-id [db & [project-id]]
  (:task-id (review-task-state db project-id)))

(reg-sub :review/task-id
         (fn [db [_ & [project-id]]]
           (review-task-id db project-id)))

(defn- load-review-task [db project-id article-id today-count]
  (-> db
      (assoc-in [:data :review project-id :task-id] article-id)
      (assoc-in [:data :review project-id :today-count] today-count)))

(def-data :review/task
  :loaded? (fn [db project-id] (review-task-id db project-id))
  :uri (constantly "/api/label-task")
  :prereqs (fn [project-id] [[:project project-id]])
  :content (fn [project-id] {:project-id project-id})
  :process
  (fn [{:keys [db]} [project-id] {:keys [article labels notes today-count] :as result}]
    (if (= result :none)
      {:db (load-review-task db project-id :none nil)}
      (let [article (merge article {:labels labels :notes notes})]
        (cond-> {:db (-> (load-review-task db project-id (:article-id article) today-count)
                         (article/load-article article))}
          (= (active-panel db) [:project :review])
          (merge {:scroll-top true}))))))

(def-action :review/send-labels
  :uri (constantly "/api/set-labels")
  :content (fn [project-id {:keys [article-id label-values change? confirm? resolve?]}]
             {:project-id project-id
              :article-id article-id
              :label-values label-values
              :confirm? (boolean confirm?)
              :resolve? (boolean resolve?)
              :change? (boolean change?)})
  :process
  (fn [_ [_ {:keys [on-success]}] _]
    (when on-success
      (let [success-fns    (->> on-success (remove nil?) (filter fn?))
            success-events (->> on-success (remove nil?) (remove fn?))]
        (doseq [f success-fns] (f))
        {:dispatch-n (concat success-events [[:review/reset-saving]
                                             [:review/reset-ui-labels]])})))
  :on-error
  (fn [{:keys [db error]} [_ _] _]
    (util/log-err ":review/send-labels ; error = %s" (pr-str error))
    #_ {:reload-page [true 500]}
    {}))

(reg-sub :review/today-count
         (fn [db [_ & [project-id]]]
           (:today-count (review-task-state db project-id))))

(reg-sub :review/on-review-task?
         :<- [:active-panel]
         (fn [panel] (= panel [:project :review])))

(reg-sub
 :review/editing-id
 :<- [:review/on-review-task?]
 :<- [:review/task-id]
 :<- [:project-articles/editing?]
 :<- [:project-articles/article-id]
 :<- [:article-view/editing?]
 :<- [:article-view/article-id]
 (fn [[on-review-task? task-aid
       project-editing? project-aid
       single-editing? single-aid]]
   (cond (and on-review-task?
              (integer? task-aid))  task-aid
         project-editing?           project-aid
         single-editing?            single-aid)))

(reg-sub
 :review/editing?
 :<- [:review/editing-id]
 (fn [editing-id]
   (if editing-id true false)))

(reg-sub
 :review/resolving?
 :<- [:project-articles/article-id]
 :<- [:project-articles/resolving?]
 :<- [:article-view/article-id]
 :<- [:article-view/resolving?]
 (fn [[project-aid project-resolving?
       single-aid single-resolving?]]
   (boolean (or (and project-aid project-resolving?)
                (and single-aid single-resolving?)))))

(reg-sub ::labels #(get-in % [:state :review :labels]))

;;https://clojuredocs.org/clojure.core/merge
;; note: "correct" version converts false->nil
;; which isn't what we want!
(defn deep-merge [v & vs]
  (letfn [(rec-merge [v1 v2]
            (if (and (map? v1) (map? v2))
              (cond
                (-> v2 meta :force-merge-override) v2
                (-> v1 meta :force-merge-override) v1
                :else
                (merge-with deep-merge v1 v2))
              v2))]
    (if (some identity vs)
      (reduce #(rec-merge %1 %2) v vs)
      (last vs))))

(reg-sub :review/active-labels
         (fn [[_ article-id _ _ _]]
           [(subscribe [:self/user-id])
            (subscribe [::labels])
            (subscribe [:article/labels article-id])])
         (fn [[user-id ui-labels article-labels]
              [_ article-id root-label-id label-id ith]]
           (let [ui-vals (get-in ui-labels [article-id] {})
                 article-vals (->> (get-in article-labels [user-id] {})
                                   (map-values :answer))
                 merged-vals (deep-merge article-vals ui-vals)]
             (cond
               ;; give all article labels
               (nil? label-id)
               merged-vals
               ;; non-grouped label
               (and (= "na" root-label-id)
                    label-id)
               (get merged-vals label-id)
               ;; grouped label
               (and (not= "na" root-label-id)
                    label-id)
               (get-in merged-vals [root-label-id :labels ith label-id])))))

(reg-sub :review/sub-group-label-answer
         (fn [[_ article-id root-label-id _ _]]
           [(subscribe [:review/active-labels article-id "na" root-label-id])])
         (fn [[answers] [_ _article-id _root-label-id sub-label-id ith]]
           (get-in answers [:labels ith sub-label-id])))

(reg-sub-raw :review/inconsistent-labels
             (fn [_ [_ article-id label-id]]
               (reaction
                (let [label-ids @(subscribe [:project/label-ids])
                      values @(subscribe [:review/active-labels article-id])
                      overall-id @(subscribe [:project/overall-label-id])
                      overall-val (some->> overall-id (get values))
                      inconsistent
                      (when (true? overall-val)
                        (->> label-ids
                             (filter
                              (fn [label-id]
                                (let [label-val (get values label-id)
                                      inclusion @(subscribe [:label/answer-inclusion
                                                             "na" label-id label-val])]
                                  (false? inclusion))))
                             (#(if (empty? %) % (conj % overall-id)))))]
                  (if label-id
                    (boolean (in? inconsistent label-id))
                    (vec inconsistent))))))

(reg-sub :review/saving?
         (fn [[_ article-id]] (subscribe [:panel-field [:saving-labels article-id]]))
         (fn [saving?] saving?))

;; Record POST action to send labels having been initiated,
;; to show loading indicator on the button that was clicked.
(reg-event-fx :review/mark-saving [trim-v]
              (fn [_ [article-id panel]]
                {:dispatch [:set-panel-field [:saving-labels article-id] true panel]}))

;; Reset state set by :review/mark-saving
(reg-event-fx :review/reset-saving [trim-v]
              (fn [_ [article-id panel]]
                (let [field (if article-id [:saving-labels article-id] [:saving-labels])]
                  {:dispatch [:set-panel-field field nil panel]})))

(reg-sub :review/change-labels?
         (fn [[_ article-id panel]]
           (subscribe [:panel-field [:transient :change-labels? article-id] panel]))
         (fn [change-labels?] change-labels?))

;; Change interface state to enable label editor for an article where
;; user has confirmed answers.
(reg-event-fx :review/enable-change-labels [trim-v]
              (fn [_ [article-id panel]]
                {:dispatch [:set-panel-field [:transient :change-labels? article-id] true panel]}))

;; Hide label editor for article where user has confirmed answers
(reg-event-fx :review/disable-change-labels [trim-v]
              (fn [_ [article-id panel]]
                {:dispatch-n
                 (list [:set-panel-field [:transient :change-labels? article-id] false panel]
                       [:review/reset-ui-labels]
                       [:review/reset-ui-notes])}))

;; this is needed for group string labels that allow multiple values
(defn filter-blank-string-labels [m]
  (let [f (fn [[k v]] (if (vector? v) [k (filterv (comp not str/blank?) v)] [k v]))]
    ;; only apply to maps
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

;; Runs the :review/send-labels POST action using label values
;; taken from active review interface.
(reg-event-fx :review/send-labels [trim-v]
              (fn [{:keys [db]} [{:keys [project-id article-id confirm? resolve? on-success]}]]
                (let [label-values (-> @(subscribe [:review/active-labels article-id])
                                       (filter-blank-string-labels))
                      change? (= :confirmed (article/article-user-status db article-id))]
                  {:dispatch-n
                   (->> [(when confirm?
                           [:review/mark-saving article-id (active-panel db)])
                         [:action [:review/send-labels
                                   project-id {:article-id article-id
                                               :label-values label-values
                                               :confirm? confirm?
                                               :resolve? resolve?
                                               :change? change?
                                               :on-success on-success}]]]
                        (remove nil?))})))

;; Reset state of locally changed label values in review interface
(reg-event-db :review/reset-ui-labels
              #(assoc-in % [:state :review :labels] {}))

(reg-event-db :set-review-interface
              (fn [db [_ interface]]
                (assoc-in db [:state :review-interface] interface)))

(reg-event-fx :set-review-annotation-interface
              (fn [{:keys [db]} [_ context annotation-data annotations]]
                {:dispatch-n [[:set-review-interface :annotations]
                              [:reset-annotations context annotations]]
                 :db (assoc-in db [:state :annotation-label] annotation-data)}))

(reg-sub ::review-interface-override #(get-in % [:state :review-interface]))

(reg-sub ::review-interface-override #(get-in % [:state :review-interface]))

(reg-sub :review-interface
         :<- [:active-project-id]
         :<- [:visible-article-id]
         :<- [:review/editing-id]
         :<- [::review-interface-override]
         (fn [[project-id article-id editing-id override]]
           (when (and project-id article-id editing-id)
             (or override :labels))))
