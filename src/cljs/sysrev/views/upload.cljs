(ns sysrev.views.upload
  (:require [reagent.core :as r]
            [sysrev.util :refer [random-id]]
            [cljsjs.dropzone]
            [re-frame.core :refer [subscribe dispatch]]))

(defn basic-text-button [id & args]
  [:div.ui.button {:id id :style {:cursor "pointer"}}
   [:i.green.add.circle.icon]
   (first args)])

(defn upload-container
  "Create uploader form component."
  [childer upload-url on-success & args]
  (let [id (random-id)
        csrf-token (subscribe [:csrf-token])
        opts {:url upload-url
              :headers (when-let [token @csrf-token]
                         {"x-csrf-token" token})
              :addedfile #(dispatch [:notify [:div [:i.ui.large.green.circular.checkmark.icon] "File uploaded"]])
              :success on-success}]
    (letfn [(make-uploader [url]
              (js/Dropzone. (str "div#" id) (clj->js opts)))]
      (r/create-class {:reagent-render (fn [childer upload-url _ & args]
                                         (apply childer id args))
                       :component-did-mount #(make-uploader upload-url)
                       :display-name "upload-container"}))))
