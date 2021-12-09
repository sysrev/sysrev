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
  (let [listeners (atom nil)
        previous-disabled-elements (atom nil)
        viewer (r/atom nil)]
    (r/create-class
     {:display-name "Sysrev PDF.js Express Viewer"
      :component-did-mount
      (fn [this]
        (-> (WebViewer
             #js{:initialDoc url
                 :path "/js/pdfjs-express"}
             (first (.-children (rdom/dom-node this))))
            (.then (fn [^Object vwr]
                     (reset! viewer vwr)
                     (let [^Object core (.-Core vwr)]
                       (.addEventListener
                        ^Object (.-annotationManager core)
                        "annotationChanged"
                        #(some-> @listeners :on-annotation-changed (apply %&))))))))
      :reagent-render
      (fn [{:keys [annotations disabled-elements disabled-tools features on-annotation-changed theme]}]
        (reset! listeners
                {:on-annotation-changed on-annotation-changed})
        (let [^Object vwr @viewer
              ^Object ui (when vwr (.-UI vwr))
              reenabled-elements (remove (set disabled-elements) @previous-disabled-elements)]
          (when ui
            (when theme (.setTheme ui theme))
            (let [features (set (or features default-features))]
              (.disableFeatures ui (clj->js (remove features all-features)))
              (.enableFeatures ui (clj->js (vec features)))
              (if (features "Annotations")
                (.enableElements ui (clj->js annotation-toolbar-groups))
                (.disableElements ui (clj->js annotation-toolbar-groups)))
              (when (seq reenabled-elements)
                (.enableElements ui (clj->js reenabled-elements)))
              (when (seq disabled-elements)
                (.disableElements ui (clj->js disabled-elements)))
              (reset! previous-disabled-elements disabled-elements))
            ;; Synchronize annotations
            (let [^Object ann-mgr (.-annotationManager ^Object (.-Core vwr))]
              (doseq [^Object a (.getAnnotationsList ann-mgr)]
                (when-not (get @annotations (.-Id a))
                  (.deleteAnnotations ann-mgr #js[a])))))
          [:div {:style {:height (* 0.98 js/window.innerHeight)}}
           [:div {:style {:display (when-not vwr "none")
                          :height "100%"}}]]))})))
