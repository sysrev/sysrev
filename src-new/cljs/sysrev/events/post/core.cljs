(ns sysrev.events.post.core
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe dispatch dispatch-sync trim-v]]
   [day8.re-frame.http-fx]
   [sysrev.base :refer [ga-event]]
   [sysrev.shared.util :refer [in? to-uuid]]
   [sysrev.events.ajax :refer [reg-event-ajax reg-event-ajax-fx run-ajax]]
   [sysrev.routes :refer [nav-scroll-top]]))

(reg-event-fx
 :clear-query-cache
 (fn [db]
   (run-ajax
    {:method :post
     :uri "/api/clear-query-cache"
     :on-success [::process-clear-query-cache]})))

(reg-event-ajax-fx
 ::process-clear-query-cache
 (fn [_ [result]]
   {}))
