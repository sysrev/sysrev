(ns sysrev.pdf
  (:require ;;[cljsjs.pdfjs]
   [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
   [goog.dom :as dom]
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer [dispatch]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.util :refer [random-id]]
   [sysrev.views.upload :refer [upload-container basic-text-button]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def initial-pdf-view-state {:pdf-doc nil
                             :page-count nil
                             :page-num 1
                             :page-rendering false
                             :page-num-pending nil
                             :scale 1.5
                             :container-id nil})

(def state (r/atom {:pdf-view initial-pdf-view-state}))

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

(defn PDFModal
  [{:keys [trigger]} child]
  [Modal {:trigger (r/as-element trigger)
          :size "large"
          :on-close (fn []
                      (reset! (r/cursor state [:pdf-view]) initial-pdf-view-state))}
   ;;[ModalContent child]
   child
   ])

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
  :loaded? (fn [_ article-id _]
             (constantly false))
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
             {:dispatch [:fetch [:pdf/article-pdfs article-id]]}))
;; https://www.nlm.nih.gov/pubs/factsheets/dif_med_pub.html
;; 24 Million for NLM
;; 27 Million for PubMed
;; "PMC serves as a digital counterpart to the NLM extensive print journal collection"
;; yet PMC returns more results for search terms then
;; PMC results have PMIDs, but not vicea versa
;; in our database, we have 137298 total articles
;; of those, 37287 contains text like '%pmc%'. 27% of articles

;; search PubMed by PMID with PMC database: <pmid>[pmid]

(def pdfjsLib
  (doto
      (goog.object/get js/window "pdfjs-dist/build/pdf")
      ($! :GlobalWorkerOptions
          (clj->js {:workerSrc "//mozilla.github.io/pdf.js/build/pdf.worker.js"}))))

;; based on various examples provided by the authors of  pdf.js
;; see: http://mozilla.github.io/pdf.js/examples/index.html#interactive-examples
;;      https://github.com/mozilla/pdf.js/tree/master/examples/components
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/simpleviewer.js
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/pageviewer.js
;; see also:
;;      https://github.com/vivin/pdfjs-text-selection-demo/blob/master/js/minimal.js
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
                    (let [pdf-page-view (js/pdfjsViewer.PDFPageView.
                                         (clj->js {:container container
                                                   :id num
                                                   :scale @scale
                                                   :defaultViewport ($ page getViewport @scale)
                                                   :textLayerFactory (js/pdfjsViewer.DefaultTextLayerFactory.)
                                                   :annotationLayerFactory (js/pdfjsViewer.DefaultAnnotationLayerFactory.)}))]
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

(defn ViewPDF
  "Given a PDF URL, view it"
  [pdf-url]
  (let [container-id (random-id)
        _ (reset! (r/cursor state [:pdf-view :container-id]) container-id)
        page-num (r/cursor state [:pdf-view :page-num])
        page-count (r/cursor state [:pdf-view :page-count])]
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
          (when-not (nil? @page-count)
            [:p (str "Page " @page-num " / " @page-count)])]
         [:div {:style {:position "relative"}}
          [:div {:id container-id}]]])
      :component-did-mount
      (fn [this]
        (let [pdf-doc (r/cursor state [:pdf-view :pdf-doc])
              page-count (r/cursor state [:pdf-view :page-count])
              loadingTask (-> ($ pdfjsLib getDocument pdf-url)
                              ($ then
                                 (fn [pdf]
                                   (reset! pdf-doc
                                           pdf)
                                   (reset! page-count
                                           ($ pdf :numPages))
                                   (render-page @page-num))))]))})))

(defn view-open-access-pdf-url
  [article-id]
  (str "/api/open-access/" article-id "/view"))

(defn OpenAccessPDF
  [article-id on-click]
  (let [available? @(r/cursor state [article-id :open-access-available?])]
    (when (nil? available?)
      (dispatch [:fetch [:pdf/open-access-available? article-id]]))
    (if available?
      [:button.ui.button
       [PDFModal {:trigger [:a {:on-click #(.preventDefault %)} "Open Access PDF"]}
        [ViewPDF (view-open-access-pdf-url article-id)]]]
      [:span {:class "empty"
              :style {:display "none"}}])))


(defn view-s3-pdf-url
  [article-id key filename]
  (str "/api/files/article/" article-id  "/view/" key "/" filename))

(defn S3PDF
  [{:keys [article-id key filename]}]
  (let [confirming? (r/atom false)]
    (fn [{:keys [article-id key filename]}]
      [:div {:style {:margin-top "1em"}}
       (when-not @confirming?
         [:div.ui.buttons
          [:button.ui.button
           filename]
          [PDFModal {:trigger [:button.ui.button {:on-click #(.preventDefault %)}
                               "View"]}
           [ViewPDF (view-s3-pdf-url article-id key filename)]]
          [:button.ui.button
           [:a {:href (str "/api/files/article/" article-id "/download/" key "/" filename)
                :target "_blank"
                :download filename}
            "Download"]]
          [:button.ui.button
           [:i {:class "remove icon"
                :on-click #(reset! confirming? true)}]]])
       (when @confirming?
         [:div.ui.negative.message
          [:div.header
           (str "Are you sure you want to delete " filename "?")]
          [:br]
          [:div.ui.button {:on-click #(do (reset! confirming? false)
                                          (dispatch [:fetch [:pdf/delete-pdf article-id key filename]]))}
           "Yes"]
          [:div.ui.blue.button {:on-click #(reset! confirming? false)}
           "No"]])])))

(defn ArticlePDFs
  [article-id]
  (dispatch [:fetch [:pdf/article-pdfs article-id]])
  [:div {:class "ui basic"}
   (doall
    (map-indexed
     (fn [i file-map]
       ^{:key (gensym i)}
       [S3PDF {:article-id article-id
             :key (:key file-map)
             :filename (:filename file-map)}])
     @(r/cursor state [article-id :article-pdfs])))])

(defn PDFs
  [article-id]
  [:div {:id "article-pdfs"
         :class "ui segment"}
   [:h4 {:class "ui dividing header"}
    "Article PDFs"]
   [:div.ui.small.form
    [:div.field
     [:div.fields
      [OpenAccessPDF article-id]]]
    [:div.field
     [:div.fields
      [ArticlePDFs article-id]]]
    [:div.field
     [:div.fields
      [upload-container basic-text-button
       (str "/api/files/article/" article-id "/upload-pdf")
       #(dispatch [:fetch [:pdf/article-pdfs article-id]])
       "Upload PDF"]]]]])
