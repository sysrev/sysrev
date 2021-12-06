(ns sysrev.views.components.pdfjs-express
  (:require ["@pdftron/pdfjs-express" :default WebViewer]
            [reagent.core :as r]
            [reagent.dom :as rdom]))

(defn Viewer [{:keys [url]} _child]
  (let [viewer (r/atom nil)]
    (r/create-class
     {:display-name "Sysrev PDF.js Express Viewer"
      :component-did-mount
      (fn [this]
        (-> (WebViewer
             #js{:initialDoc url
                 :path "/js/pdfjs-express"}
             (first (.-children (rdom/dom-node this))))
            (.then #(reset! viewer %))))
      :reagent-render
      (fn [{:keys [theme]}]
        (let [^Object vwr @viewer
              ^Object ui (when vwr (.-UI vwr))]
          (when ui
            (when theme (.setTheme ui theme)))
          [:div {:style {:height (* 0.98 js/window.innerHeight)}}
           [:div {:style {:display (when-not vwr "none")
                          :height "100%"}}]]))})))
