(ns sysrev.state.project.members
  (:require [re-frame.core :refer [subscribe reg-sub reg-sub-raw]]
            [reagent.ratom :refer [reaction]]
            [sysrev.util :refer [in?]]))

(reg-sub ::members
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         #(:members %))

(reg-sub ::member-ids
         (fn [[_ project-id]] (subscribe [::members project-id]))
         keys)

;; Interface for getting list of user-id values for members of
;; project. Filters to remove dev users, to ensure dev users are
;; always invisible by default. `include-self-admin?` is optional and
;; will prevent `:self/user-id` from being removed by the dev user
;; filter.
(reg-sub-raw :project/member-user-ids
             (fn [_ [_ project-id include-self-admin?]]
               (reaction
                (let [self-id @(subscribe [:self/user-id])
                      members @(subscribe [::members project-id])]
                  (->> (keys members)
                       (filter (fn [user-id]
                                 (or (not @(subscribe [:user/dev? user-id]))
                                     (and include-self-admin?
                                          (some-> self-id (= user-id))))))
                       (sort <))))))

;; Tests if user-id is a member of project (user-id values are not filtered).
(reg-sub :project/member?
         (fn [[_ _ project-id]]
           [(subscribe [:self/user-id])
            (subscribe [::member-ids project-id])])
         (fn [[self-id member-ids] [_ user-id _project-id]]
           (let [user-id (or user-id self-id)]
             (boolean (in? member-ids user-id)))))

(reg-sub ::member
         (fn [[_ _ project-id]]
           [(subscribe [::members project-id])
            (subscribe [:self/user-id])])
         (fn [[members self-id] [_ user-id _]]
           (get members (or user-id self-id))))

(reg-sub :member/permissions
         (fn [[_ user-id project-id]] (subscribe [::member user-id project-id]))
         #(:permissions %))

(reg-sub :member/admin?
         (fn [[_ _match-dev? user-id project-id]]
           [(subscribe [:member/permissions user-id project-id])
            (subscribe [:user/dev? user-id])])
         (fn [[permissions dev?] [_ match-dev? _ _]]
           (boolean (or (in? permissions "admin")
                        (and match-dev? dev?)))))

(reg-sub :member/resolver?
         (fn [[_ user-id project-id]]
           [(subscribe [:project/member? user-id])
            (subscribe [:member/admin? true user-id project-id])
            (subscribe [:member/permissions user-id project-id])])
         (fn [[member? admin? permissions]]
           (and member? (or admin? (in? permissions "resolve")))))

(reg-sub :member/include-count
         (fn [[_ user-id project-id]] (subscribe [::member user-id project-id]))
         #(-> % :articles :includes count))

(reg-sub :member/exclude-count
         (fn [[_ user-id project-id]] (subscribe [::member user-id project-id]))
         #(-> % :articles :excludes count))

(reg-sub :member/article-count
         (fn [[_ user-id project-id]] (subscribe [::member user-id project-id]))
         #(+ (-> % :articles :includes count)
             (-> % :articles :excludes count)))

(reg-sub :user/project-admin?
         (fn [[_ user-id]]
           [(subscribe [:self/logged-in?])
            (subscribe [:member/admin? true user-id])])
         (fn [[logged-in? admin?]]
           (boolean (and logged-in? admin?))))
