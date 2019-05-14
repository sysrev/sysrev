(ns sysrev.state.project.base
  (:require [re-frame.core :as re-frame :refer
             [subscribe reg-sub reg-sub-raw]]
            [sysrev.shared.util :refer [in?]]))

(reg-sub
 ::projects
 (fn [db]
   (get-in db [:data :project])))

(defn get-project-raw [db project-id]
  (get-in db [:data :project project-id]))

(reg-sub
 :project/raw
 :<- [::projects]
 :<- [:active-project-id]
 (fn [[projects active-id] [_ project-id]]
   (let [project-id (or project-id active-id)]
     (get projects project-id))))

(reg-sub
 :project/error?
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (let [{:keys [error]} project]
     ((comp not nil?) error))))

(reg-sub
 :project/not-found?
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (let [error-type (-> project :error :type)]
     (in? [:not-found] error-type))))

(reg-sub
 :project/unauthorized?
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])])
 (fn [[project]]
   (let [error-type (-> project :error :type)]
     (in? [:member :authentication] error-type))))

(reg-sub
 :project/loaded?
 (fn [[_ project-id]]
   [(subscribe [:project/raw project-id])
    (subscribe [:project/error? project-id])])
 (fn [[project error?]]
   (and (map? project)
        (not-empty project)
        (not error?))))

(reg-sub :project/private-not-viewable?
         (fn [db [event project-id]]
           (and (not (get-in db [:data :project project-id :settings :public-access]))
                (not= "Unlimited"
                      (get-in db [:data :project project-id :plan])))))
