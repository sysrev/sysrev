(ns sysrev.state.settings
  (:require [sysrev.base :refer [st work-state]]
            [sysrev.state.core :as st :refer [data]]
            [sysrev.state.project :as project :refer [project]]
            [sysrev.shared.util :refer [in?]]
            [sysrev.util :refer [dissoc-in]]))

(defn editing-settings? []
  (let [page (st/current-page)
        user-id (st/current-user-id)]
    (and user-id (= page :project-settings)
         (or (st/admin-user? user-id)
             (project/project-admin? user-id)))))

(defn saved-settings []
  (project :settings))

(defn active-settings []
  (merge (saved-settings)
         (if (editing-settings?)
           (st :page :project-settings :active-values)
           {})))

(defn reset-settings-fields []
  (swap! work-state
         (fn [state]
           (-> state
               (assoc-in [:page :project-settings :active-values] {})
               (assoc-in [:page :project-settings :active-inputs] {})))))

(defn parse-setting-value [skey input]
  (case skey
    :second-review-prob
    (let [n (js/parseInt input)]
      (when (and (int? n) (>= n 0) (<= n 100))
        (* n 0.01)))
    nil))

(defn render-setting [skey]
  (if-let [input (st :page :project-settings :active-inputs skey)]
    input
    (when-let [value (get (active-settings) skey)]
      (case skey
        :second-review-prob
        (when (float? value)
          (str (int (+ 0.5 (* value 100)))))
        nil))))

(defn edit-setting [skey input]
  (when (editing-settings?)
    (swap!
     work-state
     (->> [#(assoc-in % [:page :project-settings :active-inputs skey]
                      input)
           (when-let [value (parse-setting-value skey input)]
             #(assoc-in % [:page :project-settings :active-values skey]
                        value))]
          (remove nil?)
          (apply comp)))))

(defn valid-setting-input? [skey]
  (if-let [input (st :page :project-settings :active-inputs skey)]
    (= (parse-setting-value skey input)
       (get (active-settings) skey))
    true))

(defn valid-setting-inputs? []
  (every? valid-setting-input?
          (keys (st :page :project-settings :active-inputs))))

(defn settings-modified? []
  (boolean
   (and (editing-settings?)
        (not= (saved-settings) (active-settings)))))
