(ns sysrev.fx
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe dispatch trim-v reg-fx]]
   [sysrev.routes :refer [nav nav-scroll-top]]))

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
   (dispatch [:ajax-failure response])))
