(ns sysrev.views.components.pdfjs-express
  (:require ["@pdftron/pdfjs-express" :default WebViewer]
            [clojure.data.xml :as dxml]
            [clojure.string :as str]
            [medley.core :as medley]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [re-frame.core :refer [subscribe]]))

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

(defn xfdf-doc [{:keys [annotation-id selection xfdf]}]
  {:tag :xmlns.http%3A%2F%2Fns.adobe.com%2Fxfdf%2F/xfdf
   :attrs {:xmlns.http%3A%2F%2Fwww.w3.org%2FXML%2F1998%2Fnamespace/space "preserve"}
   :content [{:tag :xmlns.http%3A%2F%2Fns.adobe.com%2Fxfdf%2F/annots
              :content [(-> (assoc-in xfdf [:attrs :name] annotation-id)
                            (update :content (fnil conj [])
                                    {:tag :xmlns.http%3A%2F%2Fns.adobe.com%2Fxfdf%2F/contents
                                     :content [(or selection "")]}))]}]})

(defn event-listener [viewer listeners]
  (fn [^js vwr]
    (reset! viewer vwr)
    (.addEventListener
     ^js (.-annotationManager ^js (.-Core vwr))
     "annotationChanged"
     #(some-> @listeners :on-annotation-changed (apply (cons vwr %&))))))

(defn Viewer []
  (let [doc-loaded? (r/atom false)
        last-url (atom nil)
        listeners (atom nil)
        previous-disabled-elements (atom nil)
        viewer (r/atom nil)]
    (r/create-class
     {:display-name "Sysrev PDF.js Express Viewer"
      :component-did-mount
      (fn [this]
        (-> (WebViewer
             #js{:extension "pdf"
                 :licenseKey "7p73z8HHl8MotUxQWKHQ"
                 :path "/js/pdfjs-express"}
             (first (.-children (rdom/dom-node this))))
            (.then (event-listener viewer listeners))))
      :reagent-render
      (fn [{:keys [annotations authorization disabled-elements disabled-tools
                   document-id features
                   on-annotation-changed read-only? theme url]}]
        (reset! listeners
                {:on-annotation-changed on-annotation-changed})
        (let [^js vwr @viewer
              ^js doc-viewer (when vwr (.-documentViewer ^js (.-Core vwr)))
              ^js ui (when vwr (.-UI vwr))
              reenabled-elements (remove (set disabled-elements) @previous-disabled-elements)]
          (when ui
            (when (not= url @last-url)
              (reset! last-url url)
              (reset! doc-loaded? false)
              (if (seq url)
                (let [opts (cond-> {:extension "pdf"}
                             (seq authorization) (assoc :customHeaders #js{"Authorization" authorization}))]
                  (.loadDocument ui url (clj->js opts))
                  (letfn [(loaded-listener []
                            (reset! doc-loaded? true)
                            (.removeEventListener doc-viewer "documentLoaded" loaded-listener))]
                    (.addEventListener doc-viewer "documentLoaded" loaded-listener)))
                (.closeDocument ui)))
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
            (when @doc-loaded?
              (let [anns (some-> annotations deref
                                 (#(when (map? %)
                                     (->> (medley/map-keys str %) ; Convert UUIDs
                                          (medley/filter-vals (comp (partial = document-id) :document-id))))))
                    ^js ann-mgr (.-annotationManager ^js (.-Core vwr))
                    ann-list (.getAnnotationsList ann-mgr)
                    existing-ids (into #{} (map #(.-Id ^object %) ann-list))]
                (.setCurrentUser ann-mgr @(subscribe [:user/username]))
                (doseq [[id m] anns]
                  (when-not (existing-ids id)
                    (.importAnnotations ann-mgr (dxml/emit-str (xfdf-doc m)))))
                (doseq [^js a ann-list]
                  (when-not (get anns (.-Id a))
                    (.deleteAnnotations ann-mgr #js[a])))))
            ;; Ensure that we aren't using an annotation tool in read-only mode (#80)
            ;; Must come after the annotations updates
            (when (and read-only?
                       (some-> ^js (.getToolMode ui)
                               .-name str/lower-case (str/includes? "annotation")))
              (.setToolMode ui "TextSelect")))
          [:div {:style {:height (* 0.98 js/window.innerHeight)}}
           [:div {:style {:display (when-not vwr "none")
                          :height "100%"}}]]))})))
