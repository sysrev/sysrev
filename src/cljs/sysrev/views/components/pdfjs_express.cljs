(ns sysrev.views.components.pdfjs-express
  (:require ["@pdftron/pdfjs-express" :default WebViewer]
            [reagent.core :as r]
            [reagent.dom :as rdom]))

(defn Viewer [{:keys [url]} _child]
  (let [viewer (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (reset! viewer
                (WebViewer
                 #js{:initialDoc url
                     :path "/js/pdfjs-express"}
                 (rdom/dom-node this))))
      :reagent-render
      (fn [_]
        [:div {:style {:height (* 0.98 js/window.innerHeight)}}])})))
