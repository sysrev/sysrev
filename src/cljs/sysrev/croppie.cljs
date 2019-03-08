(ns sysrev.croppie
  (:require [ajax.core :refer [GET POST HEAD]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [sysrev.views.components :refer [UploadButton]]
            [sysrev.views.semantic :refer [Button]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def Croppie js/Croppie)

(def error-atom (atom {}))
(defn url-exists?
  [url]
  (HEAD url
        {:handler (fn [response]
                    ;;($ js/console log response)
                    ($ js/console log "I got a response!"))
         :error-handler (fn [error]
                          (reset! error-atom error)
                          ($ js/console log (:status error))
                          ($ js/console log (clj->js error))
                          ($ js/console log "I got an error!"))}))
(defn CroppieAvatar
  [{:keys [user-id profile-picture-meta modal-open reload-avatar?]}]
  (let [croppie-instance (r/atom {})]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:div {:id "croppie-target"}]
         [Button {:on-click (fn [e]
                              (let [result ($ @croppie-instance result (clj->js
                                                                        {:type "blob"
                                                                         :format "png"}))]
                                ($ result then (fn [result]
                                                 (let [form-data (doto (js/FormData.)
                                                                   ($ append "filename" (str user-id "-avatar.png"))
                                                                   ($ append "file" result)
                                                                   ($ append "meta" ($ js/JSON stringify ($ @croppie-instance get))))]
                                                   ;; temporary, for now
                                                   ($ js/console log ($ @croppie-instance get))
                                                   (POST (str "/api/user/" user-id "/avatar")
                                                         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                                          :body form-data
                                                          :handler (fn [response]
                                                                     ($ js/console log "Successfully Uploaded")
                                                                     (reset! reload-avatar? true))
                                                          :error-handler (fn [error-response]
                                                                           ($ js/console log "Error in Upload"))})
                                                   (reset! modal-open false)
)))))}
          "Set Avatar"]])
      :component-did-mount
      (fn [this]
        (let [el ($ js/document getElementById "croppie-target")
              croppie (Croppie. el (clj->js {:boundary {:width 150
                                                        :height 150}
                                             :viewport {:type "circle"}}))]
          ;;($ @croppie-instance destroy)
          (reset! croppie-instance croppie)
          ($ @croppie-instance bind (clj->js
                                     (cond-> {:url (str "/api/user/" user-id "/profile-image")}
                                       (not (nil? profile-picture-meta)) (assoc :points (:points profile-picture-meta)
                                                                                :zoom (:zoom profile-picture-meta)))))))
      :component-will-receive-props
      (fn [this new-argv]
        (when-not (nil? @croppie-instance)
          ($ @croppie-instance destroy))
        (let [profile-picture-meta (-> new-argv second :profile-picture-meta)
              el ($ js/document getElementById "croppie-target")
              croppie (Croppie. el (clj->js {:boundary {:width 150
                                                        :height 150}
                                             :viewport {:type "circle"}}))]
          ;;($ @croppie-instance destroy)
          (reset! croppie-instance croppie)
          ($ @croppie-instance bind (clj->js
                                     (cond-> {:url (str "/api/user/" user-id "/profile-image")}
                                       (not (nil? profile-picture-meta)) (assoc :points (:points profile-picture-meta)
                                                                                :zoom (:zoom profile-picture-meta)))))))
      })))

(defn UploadProfile
  [{:keys [user-id profile-picture-exists?]}]
  [UploadButton (str "/api/user/" user-id "/profile-image")
   (fn []
     (reset! profile-picture-exists? true))
   "Upload Profile Image"])

(defn CroppieComponent
  [{:keys [user-id modal-open reload-avatar?]}]
  (let [profile-picture-exists? (r/atom false)
        profile-picture-meta (r/atom nil)
        get-meta (fn [] (GET (str "/api/user/" user-id "/profile-image/meta")
                             {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                              :handler (fn [response]
                                         (reset! profile-picture-meta (-> response :result :meta))
                                         ;;(.log js/console (clj->js @profile-picture-meta))
                                         )
                              :error-handler (fn [response])}))]
    (r/create-class
     {:reagent-render
      (fn [this]
        (if @profile-picture-exists?
          [CroppieAvatar {:user-id user-id
                          :profile-picture-meta @profile-picture-meta
                          :modal-open modal-open
                          :reload-avatar? reload-avatar?}]
          [UploadProfile {:user-id user-id
                          :profile-picture-exists? profile-picture-exists?}]))
      :get-initial-state
      (fn [this]
        (HEAD (str "/api/user/" user-id "/profile-image")
              {:handler (fn [response]
                          (get-meta)
                          (reset! profile-picture-exists? true))
               :error-handler (fn [error-response]
                                (reset! profile-picture-exists? false))}))})))
