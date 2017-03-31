(ns sysrev.ui.upload
  (:require [reagent.core :as r]
            [sysrev.util :refer [random-id]]
            [cljsjs.dropzone]
            [sysrev.state.core :as st]
            [sysrev.notify :refer [notify]]))

(defn basic-text-button [id & args]
  [:div.ui.basic.button {:id id :style {:cursor "pointer"}}
   [:i.ui.green.add.circle.icon]
   (first args)])

(defn upload-container
  "Create uploader form component."
  [childer upload-url on-success & args]
  (let [id (random-id)
        opts {:url upload-url
              :headers (when-let [csrf-token (st/csrf-token)]
                         {"x-csrf-token" csrf-token})
              :addedfile #(notify [:div [:i.ui.large.green.circular.checkmark.icon] "File uploaded"])
              :success on-success}]
    (letfn [(make-uploader [url]
              (js/Dropzone. (str "div#" id) (clj->js opts)))]
      (r/create-class {:reagent-render (fn [childer upload-url _ & args]
                                          (apply childer id args))
                       :component-did-update (fn [this [_ upload-url]]
                                               (make-uploader upload-url))
                       :component-did-mount #(make-uploader upload-url)
                       :display-name "upload-container"}))))
