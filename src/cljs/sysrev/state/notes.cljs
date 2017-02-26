(ns sysrev.state.notes
  (:require [sysrev.base :refer [st]]
            [sysrev.state.core :as st :refer [data]]
            [sysrev.state.project :refer [project]]
            [cljs-time.core :as t]))

(defn get-note-field [article-id user-id note-name]
  (data [:notes :article article-id user-id note-name]))

(defn init-note-field [article-id user-id note-name init-content]
  (fn [s]
    (assoc-in s [:data :notes :article article-id user-id note-name]
              {:saved init-content
               :active init-content
               :update-time ((t/default-ms-fn))})))

(defn ensure-note-field [article-id user-id note-name init-content]
  (if (nil? (data [:notes :article article-id user-id note-name]))
    (init-note-field article-id user-id note-name init-content)
    identity))

(defn note-field-synced? [article-id note-name]
  (when-let [user-id (st/current-user-id)]
    (if (map? (data [:notes :article article-id user-id note-name]))
      (let [{:keys [saved active]}
            (data [:notes :article article-id user-id note-name])]
        (= saved active))
      true)))

(defn update-note-field [article-id note-name content]
  (let [user-id (st/current-user-id)]
    (cond
      (nil? user-id) identity
      (map? (data [:notes :article article-id user-id note-name]))
      (fn [s]
        (update-in s [:data :notes :article article-id user-id note-name]
                   #(merge % {:active content
                              :update-time ((t/default-ms-fn))})))
      :else (init-note-field article-id user-id note-name content))))

(defn update-note-saved [article-id note-name saved-content]
  (let [user-id (st/current-user-id)]
    (cond
      (nil? user-id) identity
      (map? (data [:notes :article article-id user-id note-name]))
      (fn [s]
        (update-in s [:data :notes :article article-id user-id note-name]
                   #(merge % {:saved saved-content})))
      :else (init-note-field article-id user-id note-name saved-content))))

(defn ensure-article-note-fields [article-id nmap]
  (assert article-id)
  (assert (map? nmap))
  (fn [s]
    (reduce
     (fn [s user-id]
       (reduce
        (fn [s [note-name content]]
          (let [note-name (name note-name)]
            ((ensure-note-field article-id user-id note-name content)
             s)))
        s
        (vec (get nmap user-id))))
     s
     (keys nmap))))

(defn ensure-user-note-fields [user-id nmap]
  (assert user-id)
  (assert (map? nmap))
  (fn [s]
    (reduce
     (fn [s article-id]
       (reduce
        (fn [s [note-name content]]
          (let [note-name (name note-name)]
            ((ensure-note-field article-id user-id note-name content)
             s)))
        s
        (vec (get nmap article-id))))
     s
     (keys nmap))))
