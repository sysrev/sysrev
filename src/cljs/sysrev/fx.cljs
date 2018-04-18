(ns sysrev.fx
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe dispatch trim-v reg-fx]]
   [sysrev.nav :refer [nav nav-scroll-top]]
   [sysrev.state.core :refer [get-build-id get-build-time]]
   [sysrev.util :refer [scroll-top]]))

(reg-fx
 :scroll-top
 (fn [_]
   (scroll-top)))

(reg-fx
 :nav
 (fn [url]
   (nav url)))

(reg-fx
 :nav-scroll-top
 (fn [url]
   (nav-scroll-top url)))

(reg-fx
 :set-csrf-token
 (fn [token]
   (dispatch [:set-csrf-token token])))

(reg-fx
 :ajax-failure
 (fn [response]
   nil))

(defn- reload-page []
  (-> js/window .-location (.reload true)))

(reg-fx
 :reload-page
 (fn [[reload? delay-ms]]
   (when reload?
     (if delay-ms
       (js/setTimeout #(reload-page) delay-ms)
       (reload-page)))))

(reg-event-fx
 ::reload-on-new-build
 [trim-v]
 (fn [{:keys [db]} [build-id build-time]]
   (let [cur-build-id (get-build-id db)
         cur-build-time (get-build-time db)]
     (merge
      {:dispatch-n
       (->> (list (when (nil? cur-build-id)
                    [:set-build-id build-id])
                  (when (nil? cur-build-time)
                    [:set-build-time build-time]))
            (remove nil?))}
      (when (or (and build-id cur-build-id
                     (not= build-id cur-build-id))
                (and build-time cur-build-time
                     (not= build-time cur-build-time)))
        {:reload-page [true 50]})))))

(reg-fx
 :reload-on-new-build
 (fn [[build-id build-time]]
   (when (or build-id build-time)
     (dispatch [::reload-on-new-build [build-id build-time]]))))
