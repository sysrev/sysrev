(ns sysrev.pdf
  (:require [cljsjs.semantic-ui-react]
            [goog.dom :as dom]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$ $!]]
            [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-db trim-v]]
            [sysrev.base :as base]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.state.ui :as ui-state]
            [sysrev.state.article :as article]
            [sysrev.views.annotator :as annotator]
            [sysrev.views.components.core :refer [UploadButton]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.util :as util :refer [wrap-user-event]]
            [sysrev.shared.util :as sutil :refer [css]]
            [sysrev.macros :refer-macros [with-loader]]))

(def view :pdf)

(def initial-view-state
  {:visible false
   :pdf-doc nil
   :page-count nil
   :page-num 1
   :page-queue nil
   :container-id nil})

(defn pdf-url-open-access?
  "Given a pdf-url, is it an open access pdf?"
  [pdf-url]
  (boolean (re-matches #"/api/open-access/(\d+)/view/.*" pdf-url)))

(defn pdf-url->article-id
  "Given a pdf-url, return the article-id"
  [pdf-url]
  (sutil/parse-integer
   (if (pdf-url-open-access? pdf-url)
     (second (re-find #"/api/open-access/(\d+)/view" pdf-url))
     (second (re-find #"/api/files/.*/article/(\d+)/view" pdf-url)))))

(defn pdf-url->key
  "Given a pdf url, extract the key from it, if it is provided, nil otherwise"
  [pdf-url]
  (if (pdf-url-open-access? pdf-url)
    (nth (re-find #"/api/open-access/(\d+)/view/(.*)" pdf-url) 2)
    (nth (re-find #"/api/files/.*/article/(\d+)/view/(.*)/.*" pdf-url) 2)))

(defn get-ann-context [pdf-url & [project-id]]
  {:class "pdf"
   :project-id (or project-id @(subscribe [:active-project-id]))
   :article-id (pdf-url->article-id pdf-url)
   :pdf-key (pdf-url->key pdf-url)})

(reg-sub
 :pdf-cache
 (fn [db [_ url]]
   (get-in db [:data :pdf-cache url])))

(reg-event-db
 :pdf-cache
 [trim-v]
 (fn [db [url pdf]]
   (assoc-in db [:data :pdf-cache url] pdf)))

(reg-sub
 ::get
 :<- [:view-field view []]
 (fn [view-state [_ context path]]
   (get-in view-state (concat [context] path))))

(reg-event-db
 ::set
 [trim-v]
 (fn [db [context path value]]
   (let [full-path (concat [context] path)]
     (ui-state/set-view-field db view full-path value))))

(reg-event-db
 :pdf/init-view-state
 [trim-v]
 (fn [db [context & [panel]]]
   (ui-state/set-view-field db view [context] initial-view-state panel)))

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
(when (= (base/app-id) :main)
  (def pdfjsViewer js/pdfjsViewer)
  (def PDFPageView ($ pdfjsViewer :PDFPageView))
  (def DefaultTextLayerFactory ($ pdfjsViewer :DefaultTextLayerFactory))
  (def DefaultAnnotationLayerFactory ($ pdfjsViewer :DefaultAnnotationLayerFactory)))

(def-data :pdf/open-access-available?
  :loaded? (fn [db project-id article-id]
             (contains? (article/get-article db article-id)
                        :open-access-available?))
  :uri (fn [_ article-id] (str "/api/open-access/" article-id "/availability"))
  :prereqs (fn [project-id article-id]
             [[:article project-id article-id]])
  :process (fn [{:keys [db]} [_ article-id] {:keys [available? key]}]
             {:db (article/update-article db article-id {:open-access-available? available?
                                                         :key key})}))

(def-data :pdf/article-pdfs
  :loaded? (fn [db project-id article-id]
             (-> (article/get-article db article-id)
                 (contains? :pdfs)))
  :uri (fn [project-id article-id]
         (str "/api/files/" project-id "/article/" article-id "/article-pdfs"))
  :prereqs (fn [project-id article-id]
             [[:article project-id article-id]])
  :process (fn [{:keys [db]} [_ article-id] {:keys [files]}]
             {:db (article/update-article db article-id {:pdfs files})}))

(def-action :pdf/delete-pdf
  :uri (fn [project-id article-id key filename]
         (str "/api/files/" project-id "/article/" article-id "/delete/" key))
  :process (fn [_ [project-id article-id _ _] _]
             {:dispatch [:reload [:pdf/article-pdfs project-id article-id]]}))

;; search PubMed by PMID with PMC database: <pmid>[pmid]
;;
;; based on various examples provided by the authors of pdf.js
;; see: http://mozilla.github.io/pdf.js/examples/index.html#interactive-examples
;;      https://github.com/mozilla/pdf.js/tree/master/examples/components
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/simpleviewer.js
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/pageviewer.js
;; see also:
;;      https://github.com/vivin/pdfjs-text-selection-demo/blob/master/js/minimal.js
;;
;; this was ultimately based off of
;; https://github.com/mozilla/pdf.js/blob/master/examples/components/pageviewer.js

(defn render-page
  "Render page num"
  [context pdf num]
  (->
   ($ pdf getPage num)
   ($ then
      (fn [page]
        (let [{:keys [container-id]} @(subscribe [::get context])
              container (and container-id
                             ($ js/document getElementById container-id))]
          (if-not container
            (dispatch-sync [::set context [:page-rendering] false])
            (let [ ;; Try to auto-adjust PDF size to containing element.
                  cwidth ($ (js/$ container) width)
                  ;; this should be 1.0? but result is too big.
                  pwidth ($ ($ page getViewport 1.35) :width)
                  scale (/ cwidth pwidth)]
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
                ($ pdf-page-view draw))
              (let [{:keys [page-queue]} @(subscribe [::get context])]
                (dispatch-sync [::set context [:page-queue] (drop 1 page-queue)]))
              (let [{:keys [page-queue]} @(subscribe [::get context])]
                (if (not-empty page-queue)
                  (render-page context pdf (first page-queue))
                  (dispatch-sync [::set context [:page-rendering] false]))))))))))

(defn queue-render-page
  "Renders page, or queues it to render next if a page is currently rendering."
  [context pdf num]
  (let [{:keys [page-rendering page-queue]} @(subscribe [::get context])]
    (dispatch-sync [::set context [:page-queue] (concat page-queue [num])])
    (when-not page-rendering
      (dispatch-sync [::set context [:page-rendering] true])
      (js/setTimeout #(render-page context pdf num) 25))))

(defn PDFContent [{:keys [pdf-url]}]
  (let [container-id (sutil/random-id)
        get-pdf-url #(:pdf-url (r/props %))
        project-id @(subscribe [:active-project-id])
        before-update
        (fn [pdf-url]
          (let [context (get-ann-context pdf-url project-id)]
            (dispatch-sync [::set context [:container-id] container-id])
            (dispatch-sync [::set context [:pdf-updating] true])
            ;; PDF annotations not currently supported
            #_ (dispatch [:require (annotator/annotator-data-item context)])
            #_ (dispatch [:reload (annotator/annotator-data-item context)])))
        render-pdf
        (fn [context pdf]
          (let [{:keys [page-num]} @(subscribe [::get context])]
            (dispatch-sync
             [::set context [:pdf-doc] pdf])
            (dispatch-sync
             [::set context [:page-count] ($ pdf :numPages)])
            (queue-render-page context pdf page-num)))
        after-update
        (fn [pdf-url]
          (let [context (get-ann-context pdf-url project-id)
                cached-pdf @(subscribe [:pdf-cache pdf-url])]
            (if cached-pdf
              (do (render-pdf context cached-pdf)
                  (dispatch-sync [::set context [:pdf-updating] false]))
              (-> (doto ($ js/pdfjsLib getDocument pdf-url)
                    ($! :GlobalWorkerOptions
                        (clj->js {:workerSrc
                                  "//mozilla.github.io/pdf.js/build/pdf.worker.js"})))
                  ($ then
                     (fn [pdf]
                       (dispatch-sync [:pdf-cache pdf-url pdf])
                       (render-pdf context pdf)
                       (dispatch-sync [::set context [:pdf-updating] false])))))))]
    (r/create-class
     {:render
      (fn [this]
        (let [pdf-url (get-pdf-url this)
              context (get-ann-context pdf-url project-id)
              {:keys [page-rendering pdf-updating]} @(subscribe [::get context])]
          [:div.view-pdf {:class (css [page-rendering "rendering"]
                                      [pdf-updating "updating"])}
           [:div.ui.grid.view-pdf-main
            [:div.sixteen.wide.column.pdf-content
             [:div.pdf-container {:id container-id}]
             ;; if annotations are ever capturable in a pdf
             ;; enable them here
             #_ [annotator/AnnotationCapture context
                 [:div.pdf-container {:id container-id}]]]]]))
      :component-will-mount
      (fn [this]
        (let [pdf-url (get-pdf-url this)
              context (and pdf-url (get-ann-context pdf-url project-id))]
          (when (and context (empty? @(subscribe [::get context])))
            (dispatch-sync [:pdf/init-view-state context])))
        (some-> (get-pdf-url this) before-update))
      :component-did-mount
      (fn [this] (some-> (get-pdf-url this) after-update))
      :component-will-update
      (fn [this new-argv]
        (let [new-pdf-url (-> new-argv second :pdf-url)
              old-pdf-url (get-pdf-url this)]
          (let [new-context (and new-pdf-url (get-ann-context
                                              new-pdf-url project-id))]
            (when (and new-context (empty? @(subscribe [::get new-context])))
              (dispatch-sync [:pdf/init-view-state new-context])))
          (when (and new-pdf-url old-pdf-url
                     (not= new-pdf-url old-pdf-url))
            (before-update new-pdf-url))))
      :component-did-update
      (fn [this old-argv]
        (let [old-pdf-url (-> old-argv second :pdf-url)
              new-pdf-url (get-pdf-url this)]
          (when (and new-pdf-url old-pdf-url
                     (not= new-pdf-url old-pdf-url))
            (after-update new-pdf-url))))})))

(defn ViewPDF [{:keys [pdf-url entry] :as args}]
  (when pdf-url
    (let [context (get-ann-context pdf-url)
          {:keys [pdf-doc page-num page-count page-rendering]} @(subscribe [::get context])
          panel @(subscribe [:active-panel])]
      [:div.ui.segments.view-pdf-wrapper
       [:div.ui.attached.two.column.grid.segment
        [:div.column
         [:h4 (:filename entry)]]
        [:div.right.aligned.column
         [ListPager {:panel panel
                     :instance-key [pdf-url]
                     :offset (dec page-num)
                     :total-count (or page-count 1)
                     :items-per-page 1
                     :item-name-string ""
                     :set-offset #(do (dispatch-sync [::set context [:page-num] (inc %)])
                                      (queue-render-page context pdf-doc (inc %)))
                     :loading? nil}]]]
       [:div.ui.center.aligned.attached.segment.pdf-content-wrapper
        [PDFContent args]]])))

(defn view-open-access-pdf-url [article-id key]
  (str "/api/open-access/" article-id "/view/" key))

(defn OpenAccessPDF [article-id]
  (when @(subscribe [:article/open-access-available? article-id])
    [:div.field>div.fields
     [:div
      [:a.ui.labeled.icon.button
       {:href (view-open-access-pdf-url
               article-id @(subscribe [:article/key article-id]))
        :target "_blank"
        :download (str article-id ".pdf")}
       [:i.download.icon]
       (str article-id ".pdf")]]]))

(defn view-s3-pdf-url [project-id article-id key filename]
  (str "/api/files/" project-id "/article/" article-id "/view/" key))

(defn S3PDF [{:keys [article-id key filename]}]
  (let [confirming? (r/atom false)]
    (fn [{:keys [article-id key filename]}]
      (let [project-id @(subscribe [:active-project-id])]
        [:div
         (when-not @confirming?
           [:div
            [:a.ui.labeled.icon.button
             {:href (str "/api/files/" project-id "/article/" article-id "/download/" key)
              :target "_blank"
              :download filename}
             [:i.download.icon]
             filename]
            [:button.ui.icon.button
             {:on-click (wrap-user-event #(reset! confirming? true))}
             [:i.times.icon]]])
         (when @confirming?
           [:div.ui.negative.message.delete-pdf
            [:div.header
             (str "Are you sure you want to delete " filename "?")]
            [:div.ui.two.column.grid
             [:div.column
              [:div.ui.fluid.button
               {:on-click
                (wrap-user-event
                 #(do (reset! confirming? false)
                      (dispatch [:action [:pdf/delete-pdf
                                          project-id article-id key filename]])))}
               "Yes"]]
             [:div.column
              [:div.ui.fluid.blue.button
               {:on-click (wrap-user-event #(reset! confirming? false))}
               "No"]]]])]))))

(defn ArticlePDFs [article-id]
  (when-let [pdfs (seq (->> @(subscribe [:article/pdfs article-id])
                            (remove :open-access?)))]
    [:div (doall (map-indexed (fn [i file-map] ^{:key i}
                                [:div.field>div.fields>div
                                 [S3PDF {:article-id article-id
                                         :key (:key file-map)
                                         :filename (:filename file-map)}]])
                              pdfs))]))

(defn PDFs [article-id]
  (when article-id
    (when-let [project-id @(subscribe [:active-project-id])]
      (with-loader [[:article project-id article-id]
                    [:pdf/article-pdfs project-id article-id]] {}
        (let [project-id @(subscribe [:active-project-id])
              full-size? (util/full-size?)
              #_ loading? #_ #(loading/any-loading? :only :pdf/open-access-available?)
              upload-form (fn []
                            [:div.field>div.fields
                             [UploadButton
                              (str "/api/files/" project-id
                                   "/article/" article-id "/upload-pdf")
                              #(dispatch [:reload [:pdf/article-pdfs project-id article-id]])
                              "Upload PDF"
                              nil {:margin 0}]])
              open-access? @(subscribe [:article/open-access-available? article-id])
              logged-in? @(subscribe [:self/logged-in?])
              member? @(subscribe [:self/member?])
              authorized? (and logged-in? member?)]
          (dispatch [:require [:pdf/open-access-available?
                               project-id article-id]])
          (when (or authorized? open-access?)
            [:div#article-pdfs.ui.segment>div.ui.grid>div.row
             [:div.left.aligned
              {:class (css [full-size? "twelve" :else "eleven"] "wide column")}
              [:div {:class (css "ui" [full-size? "small" :else "tiny"] "form")}
               [OpenAccessPDF article-id]
               ;; need better permissions for PDFs, for now, simple don't allow
               ;; people who aren't logged in to view PDFs
               (when logged-in?
                 [ArticlePDFs article-id])]]
             (when logged-in?
               [:div.right.aligned
                {:class (css [full-size? "four" :else "five"] "wide column")}
                [upload-form]])]))))))
