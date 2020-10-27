(ns sysrev.views.uppy
  (:require [reagent.core :as r]
            ["@uppy/core" :refer [Uppy]]
            ["@uppy/react" :refer [Dashboard] :rename {Dashboard ReactDashboard}]
            ["@uppy/xhr-upload" :as XHRUpload]
            ["uuid" :refer [v4] :rename {v4 uuidv4}]))

;; rational for the way the uppy object is handled here:
;; from: https://uppy.io/docs/xhr-upload/#bundle-false
;; With the option bundle = true, only local uploads (drag-drop, browse, webcam) are allowed 
;; Uppy won’t be able to bundle remote files (from Google Drive or Instagram), and will throw an error in this case.
;;
;; 1. with the option bundle = false, uppy will upload each
;;    file as a separate request
;; 2. A collection of documents should be grouped together
;; 3. In order to group a set of requests, the header
;;    "uppy-grouping-uuid" will include a grouping uuid
;; 4. the uppy object must be changed each time a new set of uploads occurs
;;
;; This is not currently implemented server side because it requires a Companion service https://uppy.io/docs/
;; therefore, the code remains and just bundles the xhr multiparam request

(def ReagentDashboard (r/adapt-react-class ReactDashboard))

(defn uppy-setup [{:keys [uppy-atom uppy-options xhr-options on-complete]
                   :or {on-complete (constantly true)}}]
  (doto @uppy-atom
    (.setOptions (clj->js
                  uppy-options))
    (.use XHRUpload (clj->js
                     xhr-options))
    #_(.on "upload-success"
           (fn [_result]
             (.log js/console "upload was successful")))
    (.on "upload-error"
         (fn [_result]
           (.log js/console "upload was a failure")))
    (.on "complete"
         (fn [_result]
           ;; reload the sources
           ;; on each complete upload,
           ;; change the uppy-grouping-uuid
           (on-complete)
           (.close @uppy-atom)
           (reset! uppy-atom (Uppy.))
           
           (uppy-setup {:uppy-atom uppy-atom
                        :uppy-options uppy-options
                        :xhr-options (assoc-in xhr-options
                                               [:headers "uppy-grouping-uuid"] (uuidv4))})))))
(defn Dashboard [{:keys [endpoint csrf-token on-complete]}]
  (let [uppy (r/atom (Uppy.))]
    (uppy-setup {:uppy-atom uppy
                 :uppy-options {:restrictions
                                {:allowedFileTypes ["application/pdf"]}}
                 :xhr-options 
                 {:endpoint endpoint
                  :bundle true 
                  :headers {"x-csrf-token" csrf-token
                            "uppy-grouping-uuid" (uuidv4)}}
                 :on-complete on-complete})
    (r/create-class
     {:render (fn []
                [ReagentDashboard {:uppy @uppy
                                   :proudlyDisplayPoweredByUppy false
                                   :height 350}])
      :component-will-unmount (fn [_this]
                                (.close @uppy))})))