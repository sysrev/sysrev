;; Sets up state, history, and provides functions to manipulate them

(ns sysrev-web.base
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [pushy.core :as pushy]
            [cljs.pprint :refer [pprint]]))

(defonce state (r/atom {:page 0
                        :criteria {}
                        :ranking-page 0
                        ;; FIFO for notifications
                        :notifications #queue []
                        ;; FIFO for upcoming articles
                        :label-activity #queue []
                        ;; LIFO for skipped articles
                        :label-skipped '()}))

(defonce server-data (r/atom {}))

(secretary/set-config! :prefix "")

(defonce history
  (pushy/pushy secretary/dispatch!
               (fn [x] (when (secretary/locate-route x) x))))

(defn history-init []
  (pushy/start! history))

(defn state-val [ks]
  (get-in @state ks))

(defn state-set [ks val]
  (swap! state #(assoc-in % ks val)))

(defn notify
  "enqueue a notification"
  [message]
  (swap! state update :notifications #(conj % message))
  (js/setTimeout #(swap! state update :notifications pop) 2000))

(defn notify-pop
  "Removes the oldest notification"
  []
  (swap! state update :notifications pop))

(defn notify-head
  "Get the oldest notification"
  []
  (-> @state :notifications first))

(enable-console-print!)

(defn debug-container [child & children]
  (fn [child & children]
    [:div {:style {:background-color "lightgrey"}}
     child
     children]))

(defn show-debug-box
  ([title obj] [debug-container [:h1 {:key "title"} title] [:pre {:key "data"} (with-out-str (pprint obj))]])
  ([obj] (show-debug-box "" obj)))

(defn debug-box [arg & args]
  (apply show-debug-box arg args))

(defn scrollTop []
  (. js/window (scrollTo 0 0)))

(defn nav!
  "takes a function which returns a route to navigate to"
  [to-route-f]
  (pushy/set-token! history (to-route-f))
  (scrollTop))

;; The classification task is driven by a vector holding articles
;; Articles received from the server are inserted to the right.
;; Skipped articles are placed on a stack (list) and put back onto the left.
(defn label-queue [] (:label-activity @state))

(defn label-queue-head
  "Get the article at the front of the label task"
  []
  (first (label-queue)))

(defn label-queue-pop
  "Drop the item at the front. Skip/finish"
  []
  (swap! state update :label-activity pop))

(defn label-queue-right-append
  "Add more articles to label task (to the right)"
  [items]
  (swap! state update :label-activity #(into % items)))

(defn label-queue-left-append
  "Put back an article into the label task (add to left/front)
  With queue, this involves copying the whole queue and inserting it back."
  [items]
  (swap! state update :label-activity #(into (into #queue [] items) %)))

(defn label-skipped-push
  "Put an article on top of the skipped stack."
  [head]
  (swap! state update :label-skipped #(conj % head))
  (swap! state assoc :criteria {}))

(defn label-skip
  "Take an article off of the front of the task queue, and put it on the skipped stack"
  []
  (let [head (label-queue-head)]
    (when-not (nil? head)
      (label-skipped-push head)
      (label-queue-pop))))

(defn label-skipped-head
  "Head of the skipped stack"
  []
  (-> @state :label-skipped first))

(defn label-skipped-pop []
  (swap! state update :label-skipped rest))

(defn label-load-skipped
  "Take an item off of the skipped stack, and put it back on the front of the queue"
  []
  (let [head (label-skipped-head)]
    (when-not (nil? head)
      (label-queue-left-append [head])  ;; This is expensive for large queue.
      (label-skipped-pop))))

;Slavish copy from stack overflow, get the position of occurence of regex, and the match.
;Modified though to use a sorted map so we can have the result sorted by index.
;https://stackoverflow.com/questions/18735665/how-can-i-get-the-positions-of-regex-matches-in-clojurescript
(defn re-pos [re s]
  (let [re (js/RegExp. (.-source re) "g")]
    (loop [res (sorted-map)]
      (if-let [m (.exec re s)]
        (recur (assoc res (.-index m) (first m)))
        res))))

(defn map-values
  "Map a function over the values of a collection of pairs (vector of vectors, hash-map, etc.) Optionally accept
  a result collection to put values into."
  ([m f rescoll]
   (into rescoll (->> m (map (fn [[k v]] [k (f v)])))))
  ([m f]
   (map-values m f {})))


(defn current-user-data []
  (let [display-user-id (:display-id (:user @state))
        user-pred (fn [u] (= display-user-id (-> u :user :id)))
        all-users (:users @server-data)]
    (first (filter user-pred all-users))))

