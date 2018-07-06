(ns sysrev.pdf
  (:require [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
            [goog.dom :as dom]
            [reagent.core :as r]
            [re-frame.core :as re-frame :refer [dispatch]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.annotation :as annotation :refer [AnnotationCapture AnnotationToggleButton AnnotationMenu]]
            [sysrev.util :refer [random-id full-size?]]
            [sysrev.views.upload :refer [upload-container basic-text-button]])
  (:require-macros [reagent.interop :refer [$ $!]]
                   [sysrev.macros :refer [with-loader]]))

(def initial-pdf-view-state {:pdf-doc nil
                             :page-count nil
                             :page-num 1
                             :page-rendering false
                             :page-num-pending nil
                             :scale 1.5
                             :container-id nil})

(defonce state (r/atom {:pdf-view initial-pdf-view-state}))

(def semantic-ui js/semanticUIReact)
(def Button (r/adapt-react-class (goog.object/get semantic-ui "Button")))
(def Header (r/adapt-react-class (goog.object/get semantic-ui "Header")))
(def ModalHeader (r/adapt-react-class
                  ($ (goog.object/get semantic-ui "Modal")
                     :Header)))
(def ModalContent (r/adapt-react-class
                   ($ (goog.object/get semantic-ui "Modal")
                      :Content)))
(def ModalDescription (r/adapt-react-class
                       ($ (goog.object/get semantic-ui "Modal")
                          :Description)))

(def Modal (r/adapt-react-class (goog.object/get semantic-ui "Modal")))

;; this in lieu of an externs file
(def pdfjsViewer js/pdfjsViewer)
(def PDFPageView ($ pdfjsViewer :PDFPageView))
(def DefaultTextLayerFactory ($ pdfjsViewer :DefaultTextLayerFactory))
(def DefaultAnnotationLayerFactory ($ pdfjsViewer :DefaultAnnotationLayerFactory))

(defn PDFModal
  [{:keys [trigger]} child]
  [Modal {:trigger (r/as-element trigger)
          :size "fullscreen"
          :on-close (fn []
                      (reset! (r/cursor state [:pdf-view]) initial-pdf-view-state))}
   [ModalContent child]])

(def-data :pdf/open-access-available?
  :loaded? (fn [_ article-id _]
             (not (nil? @(r/cursor state [article-id :open-access-available?]))))
  :uri (fn [article-id] (str "/api/open-access/" article-id "/availability"))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id])
  :process (fn [_ [article-id] result]
             (swap! state assoc-in [article-id :open-access-available?]
                    (:available? result))
             {}))

(def-data :pdf/article-pdfs
  :loaded? (fn [db article-id]
             (contains? (get-in @state [article-id]) :article-pdfs))
  :uri (fn [article-id] (str "/api/files/article/" article-id "/article-pdfs"))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id])
  :process (fn [_ [article-id] result]
             (swap! state assoc-in [article-id :article-pdfs]
                    (:files result))
             {}))

(def-data :pdf/delete-pdf
  :loaded? (fn [_ [article-id _ _] _]
             (constantly false))
  :uri (fn [article-id key filename]
         (str "/api/files/article/" article-id "/delete/" key "/" filename))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id key filename])
  :process (fn [_ [article-id key filename] result]
             {:dispatch [:reload [:pdf/article-pdfs article-id]]}))

;; search PubMed by PMID with PMC database: <pmid>[pmid]

;; based on various examples provided by the authors of pdf.js
;; see: http://mozilla.github.io/pdf.js/examples/index.html#interactive-examples
;;      https://github.com/mozilla/pdf.js/tree/master/examples/components
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/simpleviewer.js
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/pageviewer.js
;; see also:
;;      https://github.com/vivin/pdfjs-text-selection-demo/blob/master/js/minimal.js

;; this was ultimately based off of
;; https://github.com/mozilla/pdf.js/blob/master/examples/components/pageviewer.js

(defn render-page
  "Render page num"
  [num]
  ;; this should probably be more function, render-page should take a pdf object
  (let [pdf-doc (r/cursor state [:pdf-view :pdf-doc])
        page-num-pending (r/cursor state [:pdf-view :page-num-pending])
        page-rendering (r/cursor state [:pdf-view :page-rendering])]
    (reset! page-rendering true)
    (-> ($ @pdf-doc getPage num)
        ($ then (fn [page]
                  ;; this could could be more functional by including container as the parameter in render page
                  (let [scale (r/cursor state [:pdf-view :scale])
                        ;;viewport ($ page getViewport @scale)
                        container ($ js/document getElementById @(r/cursor state [:pdf-view :container-id]))
                        ;; context ($ container getContext "2d")
                        ]
                    ;; set the container dimensions
                    #_ ($! container :height ($ viewport :height))
                    #_ ($! container :width ($ viewport :width))

                    ;; remove previous divs that were in place
                    (dom/removeChildren container)
                    (let [pdf-page-view
                          (PDFPageView.
                           (clj->js {:container container
                                     :id num
                                     :scale @scale
                                     :defaultViewport ($ page getViewport @scale)
                                     :textLayerFactory
                                     (DefaultTextLayerFactory.)
                                     :annotationLayerFactory
                                     (DefaultAnnotationLayerFactory.)}))]
                      ($ pdf-page-view setPdfPage page)
                      ($ pdf-page-view draw)
                      (reset! page-rendering false)
                      (reset! page-num-pending nil))))))))

(defn queue-render-page
  "If another page rendering is in progress, waits until the rendering is finished. Otherwise, execute rendering immediately"
  [num]
  (let [page-rendering (r/cursor state [:pdf-view :page-rendering])
        page-num-pending (r/cursor state [:pdf-view :page-num-pending])]
    (if @page-rendering
      (reset! page-num-pending num)
      (render-page num))))

(defn pdf-url->key
  "Given a pdf url, extract the key from it, if it is provided, nil otherwise"
  [pdf-url]
  (nth (re-find #"/api/files/article/(\d+)/view/(.*)/.*" pdf-url) 2))

(defn pdf-url-open-access?
  "Given a pdf-url, is it an open access pdf?"
  [pdf-url]
  (boolean (re-matches #"/api/open-access/(\d+)/view"
                       pdf-url)))

(defn pdf-url->article-id
  "Given a pdf-url, return the article-id"
  [pdf-url]
  (if (pdf-url-open-access? pdf-url)
    (second (re-find #"/api/open-access/(\d+)/view"
                     pdf-url))
    (second (re-find #"/api/files/article/(\d+)/view"
                     pdf-url))))

(def annotator-state (r/atom {}))

(defn ViewPDF
  "Given a PDF URL, view it"
  [pdf-url]
  (let [container-id (random-id)
        _ (reset! (r/cursor state [:pdf-view :container-id]) container-id)
        page-num (r/cursor state [:pdf-view :page-num])
        page-count (r/cursor state [:pdf-view :page-count])
        _ (reset! annotator-state
                                (if (pdf-url-open-access? pdf-url)
                                  ;; it is open access
                                  (assoc annotation/default-annotator-state
                                         :context {:class "open access pdf"
                                                   :article-id (pdf-url->article-id pdf-url)})
                                  ;; it is not open access, assume user uploaded pdf file
                                  (assoc annotation/default-annotator-state
                                         :context {:class "pdf"
                                                   :article-id (pdf-url->article-id pdf-url)
                                                   :pdf-key (pdf-url->key pdf-url)})))]
    (r/create-class
     {:reagent-render
      (fn []
        [:div
         [:div
          [:button.ui.button
           {:on-click #(if-not (<= @page-num 1)
                         (do
                           (swap! page-num dec)
                           (queue-render-page @page-num)))}
           "Previous Page"]
          [:button.ui.button
           {:on-click #(if-not (>= @page-num @page-count)
                         (do
                           (swap! page-num inc)
                           (queue-render-page @page-num)))} "Next Page"]
          [AnnotationToggleButton annotator-state]
          (when-not (nil? @page-count)
            [:p (str "Page " @page-num " / " @page-count)])]
         [:div.ui.grid
          [:div.four.wide.column
           [AnnotationMenu annotator-state]]
          [:div.twelve.wide.column
           [AnnotationCapture annotator-state
            [:div {:id container-id}]]]]])
      :component-did-mount
      (fn [this]
        (let [pdf-doc (r/cursor state [:pdf-view :pdf-doc])
              page-count (r/cursor state [:pdf-view :page-count])
              loadingTask (-> (doto ($ js/pdfjsLib getDocument pdf-url)
                                ($! :GlobalWorkerOptions
                                    (clj->js {:workerSrc "//mozilla.github.io/pdf.js/build/pdf.worker.js"})))
                              ($ then
                                 (fn [pdf]
                                   (reset! pdf-doc pdf)
                                   (reset! page-count ($ pdf :numPages))
                                   (render-page @page-num))))]))})))

(defn view-open-access-pdf-url
  [article-id]
  (str "/api/open-access/" article-id "/view"))

(defn OpenAccessPDF
  [article-id on-click]
  (let [available? @(r/cursor state [article-id :open-access-available?])]
    (when available?
      [:div.field>div.fields
       [:div.ui.buttons
        [PDFModal {:trigger [:a.ui.button {:on-click #(.preventDefault %)}
                             [:i.expand.icon] "Open Access PDF"]}
         [ViewPDF (view-open-access-pdf-url article-id)]]
        [:a.ui.button
         {:href (view-open-access-pdf-url article-id)
          :target "_blank"
          :download (str article-id ".pdf")}
         "Download"]]])))

(defn view-s3-pdf-url
  [article-id key filename]
  (str "/api/files/article/" article-id  "/view/" key "/" filename))

(defn S3PDF
  [{:keys [article-id key filename]}]
  (let [confirming? (r/atom false)]
    (fn [{:keys [article-id key filename]}]
      [:div
       (when-not @confirming?
         [:div.ui.buttons
          [PDFModal {:trigger [:a.ui.button {:on-click #(.preventDefault %)}
                               [:i.expand.icon] filename]}
           [ViewPDF (view-s3-pdf-url article-id key filename)]]
          [:a.ui.button
           {:href (str "/api/files/article/" article-id
                       "/download/" key "/" filename)
            :target "_blank"
            :download filename}
           "Download"]
          [:a.ui.icon.button
           {:on-click #(reset! confirming? true)}
           [:i.times.icon]]])
       (when @confirming?
         [:div.ui.negative.message
          [:div.header
           (str "Are you sure you want to delete " filename "?")]
          [:br]
          [:div.ui.button
           {:on-click
            #(do (reset! confirming? false)
                 (dispatch [:fetch [:pdf/delete-pdf article-id key filename]]))}
           "Yes"]
          [:div.ui.blue.button {:on-click #(reset! confirming? false)}
           "No"]])])))

(defn ArticlePDFs [article-id]
  (let [article-pdfs (r/cursor state [article-id :article-pdfs])]
    (when (not-empty @article-pdfs)
      [:div
       (doall
        (map-indexed
         (fn [i file-map]
           ^{:key (gensym i)}
           [:div.field>div.fields>div
            [S3PDF {:article-id article-id
                    :key (:key file-map)
                    :filename (:filename file-map)}]])
         @article-pdfs))])))

(defn PDFs [article-id]
  (when article-id
    [:div#article-pdfs.ui.attached.segment
     {:style {:min-height "60px"}}
     (with-loader [[:pdf/article-pdfs article-id]
                   [:pdf/open-access-available? article-id]]
       {:dimmer :fixed :class ""}
       (let [upload-form (fn []
                           [:div.field>div.fields
                            [upload-container basic-text-button
                             (str "/api/files/article/" article-id "/upload-pdf")
                             #(dispatch [:reload [:pdf/article-pdfs article-id]])
                             "Upload PDF"]])]
         (if (full-size?)
           [:div.ui.grid
            [:div.row
             [:div.twelve.wide.left.aligned.column
              [:div.ui.small.form
               [OpenAccessPDF article-id]
               [ArticlePDFs article-id]]]
             [:div.four.wide.right.aligned.column
              [upload-form]]]]
           [:div.ui.small.form
            [OpenAccessPDF article-id]
            [ArticlePDFs article-id]
            [upload-form]])))]))
