(ns sysrev.pdf
  (:require [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
            [goog.dom :as dom]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-db trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.state.ui :as ui-state]
            [sysrev.state.articles :as articles]
            [sysrev.state.nav :refer [active-project-id]]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.components :refer [UploadButton]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil])
  (:require-macros [reagent.interop :refer [$ $!]]
                   [sysrev.macros :refer [with-loader]]))

(def view :pdf)

(def initial-view-state
  {:pdf-doc nil
   :page-count nil
   :page-num 1
   :page-rendering false
   :page-rendering-soon false
   :page-num-pending nil
   :container-id nil})

(reg-sub
 ::get
 :<- [:view-field view []]
 (fn [view-state [_ path]]
   (get-in view-state path)))

(reg-event-db
 ::set
 [trim-v]
 (fn [db [path value]]
   (ui-state/set-view-field db view path value)))

(reg-event-db
 :pdf/init-view-state
 [trim-v]
 (fn [db [& [panel]]]
   (ui-state/set-view-field db view nil initial-view-state panel)))

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
          :on-close (util/wrap-user-event
                     #(dispatch [:pdf/init-view-state]))}
   [ModalContent child]])

(def-data :pdf/open-access-available?
  :loaded? (fn [db project-id article-id]
             (contains? (articles/get-article db article-id)
                        :open-access-available?))
  :uri (fn [_ article-id] (str "/api/open-access/" article-id "/availability"))
  :prereqs (fn [project-id article-id]
             [[:identity] [:article project-id article-id]])
  :process (fn [{:keys [db]} [_ article-id] {:keys [available? key]}]
             {:db (articles/update-article
                   db article-id {:open-access-available? available?
                                  :key key})}))

(def-data :pdf/article-pdfs
  :loaded? (fn [db project-id article-id]
             (-> (articles/get-article db article-id)
                 (contains? :pdfs)))
  :uri (fn [_ article-id] (str "/api/files/article/" article-id "/article-pdfs"))
  :prereqs (fn [project-id article-id]
             [[:identity] [:article project-id article-id]])
  :process (fn [{:keys [db]} [_ article-id] {:keys [files]}]
             {:db (articles/update-article
                   db article-id {:pdfs files})}))

(def-action :pdf/delete-pdf
  :uri (fn [article-id key filename]
         (str "/api/files/article/" article-id "/delete/" key "/" filename))
  :process (fn [{:keys [db]} [article-id key filename] result]
             (let [project-id (active-project-id db)]
               {:dispatch [:reload [:pdf/article-pdfs project-id article-id]]})))

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
  [pdf num]
  ;; this should probably be more function, render-page should take a pdf object
  (let [{:keys []} @(subscribe [::get])]
    (dispatch-sync [::set [:page-rendering] true])
    (-> ($ pdf getPage num)
        ($ then
           (fn [page]
             ;; this could be more functional by including container as the
             ;; parameter in render page
             (let [{:keys [container-id]}
                   @(subscribe [::get])
                   container ($ js/document getElementById container-id)

                   ;; Try to auto-adjust PDF size to containing element.
                   cwidth ($ (js/$ container) width)
                   ;; this should be 1.0? but result is too big.
                   pwidth ($ ($ page getViewport 1.5) :width)
                   scale (/ cwidth pwidth)]
               #_
               (do (println (str "[render-page] cwidth = " cwidth))
                   (println (str "[render-page] pwidth = " pwidth))
                   (println (str "[render-page] scale = " scale)))

               ;; remove any previous divs
               (dom/removeChildren container)

               (let [pdf-page-view
                     (PDFPageView.
                      (clj->js {:container container
                                :id num
                                :scale scale
                                :defaultViewport ($ page getViewport scale)
                                :textLayerFactory
                                (DefaultTextLayerFactory.)
                                :annotationLayerFactory
                                (DefaultAnnotationLayerFactory.)}))]
                 ($ pdf-page-view setPdfPage page)
                 ($ pdf-page-view draw)
                 (dispatch-sync [::set [:page-rendering] false])
                 (dispatch-sync [::set [:page-rendering-soon] false])
                 (dispatch-sync [::set [:page-num-pending] nil]))))))))

(defn queue-render-page
  "Renders page, or queues it to render next if a page is currently rendering."
  [pdf num]
  (let [{:keys [page-rendering]} @(subscribe [::get])]
    (if page-rendering
      (dispatch-sync [::set [:page-num-pending] num])
      (render-page pdf num))))

(defn pdf-url-open-access?
  "Given a pdf-url, is it an open access pdf?"
  [pdf-url]
  (boolean (re-matches #"/api/open-access/(\d+)/view/.*"
                       pdf-url)))

(defn pdf-url->article-id
  "Given a pdf-url, return the article-id"
  [pdf-url]
  (sutil/parse-integer
   (if (pdf-url-open-access? pdf-url)
     (second (re-find #"/api/open-access/(\d+)/view"
                      pdf-url))
     (second (re-find #"/api/files/article/(\d+)/view"
                      pdf-url)))))

(defn pdf-url->key
  "Given a pdf url, extract the key from it, if it is provided, nil otherwise"
  [pdf-url]
  (if (pdf-url-open-access? pdf-url)
    (nth (re-find #"/api/open-access/(\d+)/view/(.*)" pdf-url) 2)
    (nth (re-find #"/api/files/article/(\d+)/view/(.*)/.*" pdf-url) 2)))

(defn ViewPDF
  "Given a PDF URL, view it"
  [pdf-url]
  (let [container-id (util/random-id)
        [article-id pdf-key] [(pdf-url->article-id pdf-url)
                              (pdf-url->key pdf-url)]
        project-id @(subscribe [:active-project-id])
        ann-context {:class "pdf"
                     :project-id project-id
                     :article-id article-id
                     :pdf-key pdf-key}
        ann-enabled? (subscribe [:annotator/enabled ann-context])]
    (r/create-class
     {:reagent-render
      (fn [pdf-url]
        (let [{:keys [pdf-doc page-num page-count
                      page-rendering page-rendering-soon]}
              @(subscribe [::get])

              on-prev (when (> page-num 1)
                        (util/wrap-user-event
                         #(do (dispatch-sync [::set [:page-num] (dec page-num)])
                              (queue-render-page pdf-doc (dec page-num)))))
              on-next (when (< page-num page-count)
                        (util/wrap-user-event
                         #(do (dispatch-sync [::set [:page-num] (inc page-num)])
                              (queue-render-page pdf-doc (inc page-num)))))]
          ;; get the user defined annotations
          [:div.view-pdf
           {:class (if (or page-rendering page-rendering-soon)
                     "rendering" nil)}
           [:div.navigate
            [:button.ui.large.label.button
             {:class (if on-prev nil "disabled")
              :on-click on-prev}
             "Previous"]
            [:button.ui.large.label.button
             {:class (if on-next nil "disabled")
              :on-click on-next}
             "Next"]
            (when page-count
              [:h5.ui.header.page-status
               (str "Page " page-num " of " page-count)])
            [annotator/AnnotationToggleButton
             ann-context
             :class "large"
             :on-change
             (fn []
               (let [re-render #(let [{:keys [pdf-doc page-num]}
                                      @(subscribe [::get])]
                                  (queue-render-page pdf-doc page-num))]
                 ;; Set `:page-rendering-soon` to enable CSS min-height
                 ;; on modal content for smoother looking update.
                 (dispatch-sync [::set [:page-rendering-soon] true])
                 ;; Use setTimeout to wait for CSS update to take effect
                 (js/setTimeout re-render 50)))]]
           [:div.ui.grid.view-pdf-main
            (when @ann-enabled?
              [:div.four.wide.column.pdf-annotation
               [annotator/AnnotationMenu ann-context "pdf"]])
            [:div.column.pdf-content
             {:class (if @ann-enabled? "twelve wide" "sixteen wide")}
             [annotator/AnnotationCapture ann-context
              [:div.pdf-container {:id container-id}]]]]]))
      :component-will-mount
      (fn [this]
        (dispatch-sync [::set [:container-id] container-id])
        (dispatch [:require (annotator/annotator-data-item ann-context)])
        (dispatch [:reload (annotator/annotator-data-item ann-context)]))
      :component-did-mount
      (fn [this]
        (-> (doto ($ js/pdfjsLib getDocument pdf-url)
              ($! :GlobalWorkerOptions
                  (clj->js {:workerSrc
                            "//mozilla.github.io/pdf.js/build/pdf.worker.js"})))
            ($ then
               (fn [pdf]
                 (let [{:keys [page-num]} @(subscribe [::get])]
                   #_
                   (do (println (str "pdf = " (type pdf)))
                       (println (str "numPages = " ($ pdf :numPages)))
                       (println (str "page-num = " page-num)))
                   (dispatch-sync [::set [:pdf-doc] pdf])
                   (dispatch-sync [::set [:page-count] ($ pdf :numPages)])
                   (render-page pdf page-num))))))})))

(defn view-open-access-pdf-url
  [article-id key]
  (str "/api/open-access/" article-id "/view/" key))

(defn OpenAccessPDF
  [article-id]
  (when @(subscribe [:article/open-access-available? article-id])
    [:div.field>div.fields
     [:div
      [PDFModal
       {:trigger [:a.ui.labeled.icon.button
                  {:on-click (util/wrap-user-event
                              #(do nil))}
                  [:i.expand.icon] "Open Access PDF"]}
       [ViewPDF (view-open-access-pdf-url
                 article-id @(subscribe [:article/key article-id]))]]
      [:a.ui.labeled.icon.button
       {:href (view-open-access-pdf-url
               article-id @(subscribe [:article/key article-id]))
        :target "_blank"
        :download (str article-id ".pdf")}
       [:i.download.icon]
       "Download"]]]))

(defn view-s3-pdf-url
  [article-id key filename]
  (str "/api/files/article/" article-id  "/view/" key "/" filename))

(defn S3PDF
  [{:keys [article-id key filename]}]
  (let [confirming? (r/atom false)]
    (fn [{:keys [article-id key filename]}]
      [:div
       (when-not @confirming?
         [:div
          [PDFModal
           {:trigger [:a.ui.labeled.icon.button
                      {:on-click (util/wrap-user-event
                                  #(do nil))}
                      [:i.expand.icon] filename]}
           [ViewPDF (view-s3-pdf-url article-id key filename)]]
          [:a.ui.labeled.icon.button
           {:href (str "/api/files/article/" article-id
                       "/download/" key "/" filename)
            :target "_blank"
            :download filename}
           [:i.download.icon]
           "Download"]
          [:button.ui.icon.button
           {:on-click (util/wrap-user-event
                       #(reset! confirming? true))}
           [:i.times.icon]]])
       (when @confirming?
         [:div.ui.negative.message.delete-pdf
          [:div.header
           (str "Are you sure you want to delete " filename "?")]
          [:div.ui.two.column.grid
           [:div.column
            [:div.ui.fluid.button
             {:on-click (util/wrap-user-event
                         #(do (reset! confirming? false)
                              (dispatch [:action [:pdf/delete-pdf
                                                  article-id key filename]])))}
             "Yes"]]
           [:div.column
            [:div.ui.fluid.blue.button
             {:on-click (util/wrap-user-event
                         #(reset! confirming? false))}
             "No"]]]])])))

(defn ArticlePDFs [article-id]
  (let [article-pdfs @(subscribe [:article/pdfs article-id])]
    (when (not-empty article-pdfs)
      [:div
       (doall
        (map-indexed
         (fn [i file-map]
           ^{:key (gensym i)}
           [:div.field>div.fields>div
            [S3PDF {:article-id article-id
                    :key (:key file-map)
                    :filename (:filename file-map)}]])
         (filter #(not (:open-access? %)) article-pdfs)))])))

(defn PDFs [article-id]
  (when article-id
    (when-let [project-id @(subscribe [:active-project-id])]
      (with-loader [[:article project-id article-id]
                    [:pdf/article-pdfs project-id article-id]]
        {}
        (let [full-size? (util/full-size?)
              inline-loader
              (fn []
                (when (and (loading/any-loading? :only :pdf/open-access-available?)
                           @(loading/loading-indicator))
                  [:div.ui.small.active.inline.loader
                   {:style {:margin-right "1em"
                            :margin-left "1em"}}]))
              upload-form
              (fn []
                [:div.field>div.fields
                 (when full-size?
                   (inline-loader))
                 [UploadButton
                  (str "/api/files/article/" article-id "/upload-pdf")
                  #(dispatch [:reload [:pdf/article-pdfs project-id article-id]])
                  "Upload PDF"]
                 (when (not full-size?)
                   (inline-loader))])
              open-access? @(subscribe [:article/open-access-available? article-id])
              logged-in? @(subscribe [:self/logged-in?])
              member? @(subscribe [:self/member?])]
          (dispatch [:require [:pdf/open-access-available?
                               project-id article-id]])
          (when (if (or (not logged-in?) (not member?))
                  open-access?
                  true)
            [:div#article-pdfs.ui.segment
             {:style {:min-height "60px"}}
             [:div.ui.grid
              [:div.row
               [:div.twelve.wide.left.aligned.column
                [:div.ui.small.form
                 [OpenAccessPDF article-id]
                 ;; need better permissions for PDFs, for now, simple don't allow
                 ;; people who aren't logged in to view PDFs
                 (when logged-in?
                   [ArticlePDFs article-id])]]
               (when logged-in?
                 [:div.four.wide.right.aligned.column
                  [upload-form]])]]]))))))
