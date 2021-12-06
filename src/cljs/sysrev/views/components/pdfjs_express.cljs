(ns sysrev.views.components.pdfjs-express
  (:require ["@pdftron/pdfjs-express" :default WebViewer]
            [reagent.core :as r]
            [reagent.dom :as rdom]))

(def default-features
  ["Copy"
   "Download"
   "MouseWheelZoom"
   "PageNavigation"
   "Print"
   "Ribbons"
   "Search"
   "TextSelection"
   "TouchScrollLock"])

(def ^{:doc "https://pdfjs.express/api/UI.html#.Feature"}
  all-features
  (->> default-features
       (into ["Annotations"
              "FilePicker"
              "LocalStorage"
              "MathSymbols"
              "Measurement"
              "MultipleViewerMerging"
              "NotesPanel"
              "NotesPanelVirtualizedList"
              "OutlineEditing"
              "Redaction"
              "ThumbnailMerging"
              "ThumbnailReordering"])
       sort
       vec))

(def annotation-toolbar-groups
  ["toolbarGroup-Annotate"
   "toolbarGroup-FillAndSign"
   "toolbarGroup-Insert"
   "toolbarGroup-Shapes"
   "toolbarGroup-View"])

(defn Viewer [{:keys [url]}]
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
      (fn [{:keys [features theme]}]
        (let [^Object vwr @viewer
              ^Object ui (when vwr (.-UI vwr))]
          (when ui
            (when theme (.setTheme ui theme))
            (let [features (set (or features default-features))]
              (.disableFeatures ui (clj->js (remove features all-features)))
              (.enableFeatures ui (clj->js (vec features)))
              (if (features "Annotations")
                (.enableElements ui (clj->js annotation-toolbar-groups))
                (.disableElements ui (clj->js annotation-toolbar-groups)))))
          [:div {:style {:height (* 0.98 js/window.innerHeight)}}
           [:div {:style {:display (when-not vwr "none")
                          :height "100%"}}]]))})))
