(ns sysrev.subs.project
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]
            [sysrev.util :refer [short-uuid]]))

(reg-sub
 :active-project-id
 (fn [db]
   (get-in db [:state :active-project-id])))

(reg-sub
 :projects
 (fn [db]
   (get-in db [:data :project])))

(reg-sub
 :project/raw
 :<- [:projects]
 :<- [:active-project-id]
 (fn [[projects active-id] [_ project-id]]
   (let [project-id (or project-id active-id)]
     (get projects project-id))))

(reg-sub
 :project/loaded?
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (and (map? project) (not-empty project))))

(reg-sub
 :project/name
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:name project)))

(reg-sub
 :project/uuid
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:project-uuid project)))

(reg-sub
 :project/hash
 (fn [[_ project-id]]
   [(subscribe [:project/uuid project-id])])
 (fn [[project-uuid]]
   (when project-uuid
     (short-uuid project-uuid))))

(reg-sub
 :project/invite-url
 (fn [[_ project-id]]
   [(subscribe [:project/hash project-id])])
 (fn [[project-hash]]
   (str "https://sysrev.us/register/" project-hash)))

(reg-sub
 ::stats
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:stats project)))

(reg-sub
 :project/article-counts
 (fn [[_ project-id]]
   [(subscribe [::stats project-id])])
 (fn [[stats]]
   (let [total (-> stats :articles)
         reviewed (-> stats :labels :any)]
     {:reviewed reviewed
      :unreviewed (- total reviewed)})))

(reg-sub
 :project/labeled-counts
 (fn [[_ project-id]]
   [(subscribe [::stats project-id])])
 (fn [[stats]]
   (let [single (-> stats :labels :single)
         double (-> stats :labels :double)
         pending (-> stats :conflicts :pending)
         resolved (-> stats :conflicts :resolved)
         consistent (- double pending)]
     {:single single
      :consistent consistent
      :conflict pending
      :resolved resolved})))

(reg-sub
 ::members
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (:members project)))

(reg-sub
 :project/member-user-ids
 (fn [[_ project-id]]
   [(subscribe [::members project-id])])
 (fn [[members]]
   (keys members)))
