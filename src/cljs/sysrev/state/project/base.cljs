(ns sysrev.state.project.base
  (:require [re-frame.core :refer [subscribe reg-sub]]
            [sysrev.util :refer [in?]]))

(reg-sub ::projects #(get-in % [:data :project]))

(defn get-project-raw [db project-id]
  (get-in db [:data :project project-id]))

(reg-sub :project/raw
         :<- [::projects]
         :<- [:active-project-id]
         (fn [[projects active-id] [_ project-id]]
           (get projects (or project-id active-id))))

(reg-sub :project/error
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:error project)))

(reg-sub :project/not-found?
         (fn [[_ project-id]] (subscribe [:project/error project-id]))
         (fn [error] (in? [:not-found] (:type error))))

(reg-sub :project/unauthorized?
         (fn [[_ project-id]] (subscribe [:project/error project-id]))
         (fn [error] (in? [:member :authentication] (:type error))))

(reg-sub :project/owner
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:owner project)))

(reg-sub :project/parent-project
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:parent-project project)))

(reg-sub :project/plan
         (fn [[_ project-id]] (subscribe [:project/raw project-id]))
         (fn [project] (:plan project)))

(reg-sub :project/subscription-lapsed?
         (fn [[_ project-id]]
           [(subscribe [:have? [:project project-id]])
            (subscribe [:project/raw project-id])])
         (fn [[loaded? project] _]
           (and loaded? (:subscription-lapsed? project))))
