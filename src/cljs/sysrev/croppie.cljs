(ns sysrev.croppie
  (:require [clojure.string :as str]
            [ajax.core :refer [GET POST HEAD]]
            [jborden.croppie]
            [reagent.core :as r]
            [reagent.interop :refer-macros [$ $!]]
            [re-frame.core :refer [subscribe]]
            [sysrev.views.components.core :refer [UploadButton]]
            [sysrev.views.semantic :refer [Button Loader]]))

(def Croppie js/Croppie)

(def error-atom (atom {}))

(def error-load-image "There was an error loading the image, please try again.")

(defn CroppieAvatarCreator
  [{:keys [user-id profile-picture-meta modal-open reload-avatar? profile-picture-exists?]}]
  (let [croppie-instance (r/atom nil)
        profile-picture-loaded? (r/atom false)
        profile-picture-error (r/atom "")
        bind-promise-catch (fn [error]
                             (reset! profile-picture-loaded? false)
                             (reset! profile-picture-error error-load-image))
        bind-promise-then (fn [resolve]
                            (reset! profile-picture-loaded? true))

        bind-croppie (fn [profile-picture-meta]
                       (let [el ($ js/document getElementById "croppie-target")
                             croppie (Croppie. el (clj->js {:boundary {:width 150
                                                                       :height 150}
                                                            :viewport {:type "circle"}}))
                             _ (reset! croppie-instance croppie)
                             bind-promise ($ @croppie-instance bind
                                             (clj->js
                                              (assoc {:url (str "/api/user/" user-id "/profile-image")}
                                                     :points (:points profile-picture-meta)
                                                     :zoom (:zoom profile-picture-meta))))]
                         ($ bind-promise then bind-promise-then)
                         ($ bind-promise catch bind-promise-catch)))]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div
         [:div {:id "croppie-target"}]
         (when-not (str/blank? @profile-picture-error)
           [:div [:h1 {:style {:color "black"}} @profile-picture-error]])
         (when @profile-picture-loaded?
           [:div
            [Button {:on-click
                     (fn [e]
                       (let [result ($ @croppie-instance result (clj->js
                                                                 {:type "blob"
                                                                  :format "png"}))]
                         ($ result then (fn [result]
                                          (let [form-data (doto (js/FormData.)
                                                            ($ append "filename" (str user-id "-avatar.png"))
                                                            ($ append "file" result)
                                                            ($ append "meta" ($ js/JSON stringify
                                                                                ($ @croppie-instance get))))]
                                            (POST (str "/api/user/" user-id "/avatar")
                                                  {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                                   :body form-data
                                                   :handler (fn [response]
                                                              (reset! reload-avatar? true))
                                                   :error-handler (fn [error-response]
                                                                    (reset! profile-picture-error
                                                                            "Error uploading avatar to server"))})
                                            (reset! modal-open false))))
                         ($ result catch (fn [error]
                                           (reset! profile-picture-error
                                                   "Error in setting avatar")))))}
             "Set Avatar"]
            [Button {:on-click (fn [e]
                                 (reset! profile-picture-exists? false))}
             "Upload New Image"]])])
      :component-did-mount
      (fn [this]
        (if (not (nil? profile-picture-meta))
          (bind-croppie profile-picture-meta)))
      :component-will-receive-props
      (fn [this new-argv]
        (bind-croppie (-> new-argv second :profile-picture-meta)))
      :get-initial-state
      (fn [this]
        (reset! profile-picture-error "")
        (reset! profile-picture-loaded? false)
        {})})))

(defn get-meta
  [{:keys [user-id handler error-handler]}]
  (GET (str "/api/user/" user-id "/profile-image/meta")
       {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
        :handler handler
        :error-handler error-handler}))

(defn check-profile-picture
  [{:keys [user-id handler error-handler]}]
  (HEAD (str "/api/user/" user-id "/profile-image")
        {:handler handler
         :error-handler error-handler}))

(defn UploadProfile
  [{:keys [user-id on-success]}]
  [UploadButton (str "/api/user/" user-id "/profile-image")
   on-success
   "Upload Profile Image"])

(defn CroppieComponent
  [{:keys [user-id modal-open reload-avatar?]}]
  (let [profile-picture-exists? (r/atom false)
        profile-picture-meta (r/atom nil)
        checking-profile-picture? (r/atom false)
        error-message (r/atom "")]
    (r/create-class
     {:reagent-render
      (fn [this]
        (cond (not (str/blank? @error-message))
              [:div [:h1 {:style {:color "black"}}
                     @error-message]]
              @checking-profile-picture?
              [:div [:h1 {:style {:color "black"}} "Loading Image..."]]
              @profile-picture-exists?
              [:div [CroppieAvatarCreator {:user-id user-id
                                           :profile-picture-meta @profile-picture-meta
                                           :modal-open modal-open
                                           :reload-avatar? reload-avatar?
                                           :profile-picture-exists? profile-picture-exists?}]]
              (not @profile-picture-exists?)
              [UploadProfile {:user-id user-id
                              :on-success
                              (fn []
                                (reset! checking-profile-picture? true)
                                (check-profile-picture
                                 {:user-id user-id
                                  :handler
                                  (fn [response]
                                    (reset! error-message "")
                                    (reset! profile-picture-exists? true)
                                    (get-meta {:user-id user-id
                                               :handler (fn [response]
                                                          (reset! checking-profile-picture? false)
                                                          (reset! profile-picture-meta (-> response :result :meta)))
                                               :error-handler (fn [response]
                                                                (reset! checking-profile-picture? false))}))
                                  :error-handler
                                  (fn [error-reponse]
                                    (reset! checking-profile-picture? false)
                                    (reset! error-message error-load-image))}))}]))
      :get-initial-state
      (fn [this]
        (reset! error-message "")
        (reset! checking-profile-picture? true)
        (check-profile-picture {:user-id user-id
                                :handler (fn [response]
                                           (reset! profile-picture-exists? true)
                                           (get-meta
                                            {:user-id user-id
                                             :handler (fn [response]
                                                        (reset! checking-profile-picture? false)
                                                        (reset! profile-picture-meta (-> response :result :meta)))
                                             :error-handler (fn [response]
                                                              (reset! checking-profile-picture? false))}))
                                :error-handler (fn [error-response]
                                                 (reset! checking-profile-picture? false)
                                                 (reset! profile-picture-exists? false))})
        {})})))
