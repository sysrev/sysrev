(ns sysrev-web.classify
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.ajax :as ajax]))

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
  (swap! state update :label-skipped #(conj % head)))

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

(defn label-queue-update
  ([min-length interval]
   (let [cur-len (count (label-queue))
         deficit (- min-length cur-len)
         fetch-num (if (> deficit 0) (max deficit interval) 0)
         max-dist-score (if (empty? (label-queue)) nil (:score (last (label-queue))))]
     (when (> fetch-num 0)
       (println (str "fetching scores greater than " max-dist-score))
       (ajax/pull-label-tasks fetch-num label-queue-right-append max-dist-score))))
  ([] (label-queue-update 5 1)))
