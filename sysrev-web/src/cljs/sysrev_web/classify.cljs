(ns sysrev-web.classify
  (:require [sysrev-web.base :refer
             [state server-data current-user-id on-page?]]
            [sysrev-web.ajax :as ajax]))

;; The classification task is driven by a vector holding articles
;; Articles received from the server are inserted to the right.
;; Skipped articles are placed on a stack (list) and put back onto the left.
(defn label-queue [] (:label-activity @state))

(declare label-queue-right-append update-active-criteria)

(defn label-queue-update
  "Ensure queue is full by fetching new entries from server if needed."
  ([min-length interval]
   (let [cur-len (count (label-queue))
         deficit (- min-length cur-len)
         fetch-num (if (> deficit 0) (max deficit interval) 0)
         max-dist-score (if (empty? (label-queue))
                          nil
                          (get-in @server-data [:articles
                                                (last (label-queue))
                                                :score]))]
     (when (> fetch-num 0)
       (ajax/pull-label-tasks
        fetch-num label-queue-right-append max-dist-score))))
  ([] (label-queue-update 5 1)))

(defn label-queue-head
  "Get the article at the front of the label task"
  []
  (first (label-queue)))

(defn label-queue-pop
  "Drop the item at the front. Skip/finish"
  []
  (swap! state update :label-activity pop)
  ;; After removing an item, fetch new entries from server if needed.
  (label-queue-update))

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

(defn update-active-criteria []
  (let [article-id (label-queue-head)
        user-id (current-user-id)]
    (ajax/pull-article-labels
     article-id
     (fn [response]
       (let [user-labels (get response user-id)]
         (swap! state
                #(if (contains? (:page %) :classify)
                   (assoc-in % [:page :classify :label-values]
                             user-labels)
                   %)))))))

;; Watch `state` for changes to the head of the article queue
;; and pull updated label values for the article.
(add-watch
 state :active-criteria
 (fn [k v old new]
   (when (contains? (:page new) :classify)
     (let [new-aid (-> new :label-activity first)
           old-aid (-> old :label-activity first)]
       (when (and new-aid
                  (or (not= new-aid old-aid)
                      (not (contains? (:page old) :classify))))
         (update-active-criteria))))))
