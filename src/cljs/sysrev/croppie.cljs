(ns sysrev.croppie
  (:require [clojure.string :as str]
            [ajax.core :refer [GET POST HEAD]]
            ["form-data" :as FormData]
            ["croppie" :as Croppie]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [sysrev.views.components.core :refer [UploadButton]]
            [sysrev.views.semantic :refer [Button]]))

(def error-load-image "There was an error loading the image, please try again.")

(defn CroppieAvatarCreator
  [{:keys [user-id profile-picture-meta modal-open reload-avatar? profile-picture-exists?]}]
  (let [croppie-instance (r/atom nil)
        profile-picture-loaded? (r/atom false)
        profile-picture-error (r/atom "")
        bind-promise-catch (fn [_error]
                             (reset! profile-picture-loaded? false)
                             (reset! profile-picture-error error-load-image))
        bind-promise-then (fn [_resolve]
                            (reset! profile-picture-loaded? true))
        bind-croppie
        (fn [{:keys [points zoom] :as _profile-picture-meta}]
          (reset! croppie-instance (Croppie.
                                    (js/document.getElementById "croppie-target")
                                    (clj->js {:boundary {:width 150 :height 150}
                                              :viewport {:type "circle"}})))
          (doto (.bind @croppie-instance
                       (clj->js {:url (str "/api/user/" user-id "/profile-image")
                                 :points points :zoom zoom}))
            (.then bind-promise-then)
            (.catch bind-promise-catch)))
        on-croppie-set
        (fn [result]
          (let [form-data (doto (FormData.)
                            (.append "filename" (str user-id "-avatar.png"))
                            (.append "file" result)
                            (.append "meta" (js/JSON.stringify
                                             (.get @croppie-instance))))]
            (POST (str "/api/user/" user-id "/avatar")
                  {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
                   :body form-data
                   :handler (fn [_response]
                              (reset! reload-avatar? true))
                   :error-handler (fn [_response]
                                    (reset! profile-picture-error
                                            "Error uploading avatar to server"))})
            (reset! modal-open false)))
        on-croppie-error
        (fn [_error]
          (reset! profile-picture-error "Error in setting avatar"))]
    (r/create-class
     {:reagent-render
      (fn [_]
        [:div
         [:div {:id "croppie-target"}]
         (when-not (str/blank? @profile-picture-error)
           [:div [:h1 {:style {:color "black"}} @profile-picture-error]])
         (when @profile-picture-loaded?
           [:div
            [Button {:on-click #(doto (->> (clj->js {:type "blob" :format "png"})
                                           (.result @croppie-instance))
                                  (.then on-croppie-set)
                                  (.catch on-croppie-error))}
             "Set Avatar"]
            [Button {:on-click #(reset! profile-picture-exists? false)}
             "Upload New Image"]])])
      :component-did-mount
      (fn [_this]
        (some-> profile-picture-meta (bind-croppie)))
      :component-will-receive-props
      (fn [_this new-argv]
        (bind-croppie (-> new-argv second :profile-picture-meta)))
      :get-initial-state
      (fn [_this]
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
      (fn [_]
        (cond
          (not (str/blank? @error-message))
          [:div [:h1 {:style {:color "black"}} @error-message]]
          @checking-profile-picture?
          [:div [:h1 {:style {:color "black"}} "Loading Image..."]]
          @profile-picture-exists?
          [:div [CroppieAvatarCreator {:user-id user-id
                                       :profile-picture-meta @profile-picture-meta
                                       :modal-open modal-open
                                       :reload-avatar? reload-avatar?
                                       :profile-picture-exists? profile-picture-exists?}]]
          :else
          [UploadProfile
           {:user-id user-id
            :on-success
            (fn []
              (reset! checking-profile-picture? true)
              (check-profile-picture
               {:user-id user-id
                :handler
                (fn [_response]
                  (reset! error-message "")
                  (reset! profile-picture-exists? true)
                  (get-meta {:user-id user-id
                             :handler (fn [{:keys [result]}]
                                        (reset! checking-profile-picture? false)
                                        (reset! profile-picture-meta (:meta result)))
                             :error-handler #(reset! checking-profile-picture? false)}))
                :error-handler
                (fn [_response]
                  (reset! checking-profile-picture? false)
                  (reset! error-message error-load-image))}))}]))
      :get-initial-state
      (fn [_this]
        (reset! error-message "")
        (reset! checking-profile-picture? true)
        (check-profile-picture
         {:user-id user-id
          :handler (fn [_response]
                     (reset! profile-picture-exists? true)
                     (get-meta {:user-id user-id
                                :handler (fn [{:keys [result]}]
                                           (reset! checking-profile-picture? false)
                                           (reset! profile-picture-meta (:meta result)))
                                :error-handler #(reset! checking-profile-picture? false)}))
          :error-handler (fn [_response]
                           (reset! checking-profile-picture? false)
                           (reset! profile-picture-exists? false))})
        {})})))
