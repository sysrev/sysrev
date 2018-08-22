(ns sysrev.markdown
  (:require [ajax.core :refer [GET POST PUT DELETE]]
            [cljsjs.semantic-ui-react]
            [cljsjs.showdown]
            [re-frame.core :refer [subscribe]]
            [reagent.core :as r])
  (:require-macros [reagent.interop :refer [$]]))


(def default-state {:editing? false
                    :current-description ""
                    :draft-description ""
                    :retrieving? false})
(def state (r/atom default-state))

(def semantic-ui js/semanticUIReact)
(def TextArea (r/adapt-react-class (goog.object/get semantic-ui "TextArea")))

;; security, particularly regarding XSS attacks is a big concern when letting
;; users generate their own HTML
;; see: https://github.com/showdownjs/showdown/wiki/Markdown's-XSS-Vulnerability-(and-how-to-mitigate-it)
;; unfortunately, there is little in the way in regards to mitigation at that article
;;
;; Some example of md that could cause issues:
;; "[some text](javascript:alert('xss'))"
;; a particularly nasty one:
;; > hello <a name="n"
;; > href="javascript:alert('xss')">*you*</a>
;;
;; This currently uses the https://github.com/cure53/DOMPurify
;;
;; This issue https://github.com/markedjs/marked/issues/1232 shows additional filters
;; dompurify: https://www.npmjs.com/package/dompurify
;; sanitize-html: https://www.npmjs.com/package/sanitize-html
;; insane: https://www.npmjs.com/package/insane
;; marked-sanitizer-github: https://www.npmjs.com/package/marked-sanitizer-github

(defn RenderMarkdown
  [markdown]
  (let [converter (doto (js/showdown.Converter.)
                    ($ setOption "simpleLineBreaks" true))]
    [:div {:dangerouslySetInnerHTML {:__html (->> markdown
                                                  ($ converter makeHtml)
                                                  ($ js/DOMPurify sanitize))}}]))
(defn MarkdownComponent
  [state]
  (let [editing? (r/cursor state [:editing?])
        current-description (r/cursor state [:current-description])
        draft-description (r/cursor state [:draft-description])
        retrieving? (r/cursor state [:retrieving?])
        get-description! (fn [current-description]
                           (reset! retrieving? true)
                           (GET "/api/project-description"
                                {:params {:project-id @(subscribe [:active-project-id])}
                                 :handler (fn [response]
                                            (reset! retrieving? false)
                                            (reset! current-description
                                                    (-> response
                                                        :result
                                                        :project-description)))
                                 :error-handler (fn [error]
                                                  (reset! retrieving? false)
                                                  ($ js/console log "[Error] get-description!"))}))
        create-description! (fn [markdown]
                              (reset! retrieving? true)
                              (POST "/api/project-description"
                                    {:params {:markdown markdown
                                              :project-id @(subscribe [:active-project-id])}
                                     :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                     :handler (fn [response]
                                                (reset! retrieving? false))
                                     :error-handler (fn [error]
                                                      ($ js/console log "[Error] create-description!")
                                                      (reset! retrieving? false))}))
        update-description! (fn [markdown]
                              (reset! retrieving? true)
                              (if-not (clojure.string/blank? markdown)
                                ;; was updated
                                (PUT "/api/project-description"
                                     {:params {:markdown markdown
                                               :project-id @(subscribe [:active-project-id])}
                                      :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                      :handler (fn [response]
                                                 (reset! retrieving? false))
                                      :error-handler (fn [error]
                                                       ($ js/console log "[Error] update-description")
                                                       (reset! retrieving? false))})
                                ;; was deleted
                                (DELETE "/api/project-description"
                                        {:params {:project-id @(subscribe [:active-project-id])}
                                         :headers {"x-csrf-token" @(subscribe [:csrf-token])}
                                         :handler (fn [response]
                                                    (reset! retrieving? false))
                                         :error-handler (fn [error]
                                                          ($ js/console log "[Error] delete-description")
                                                          (reset! retrieving? false))})))]
    (get-description! (r/cursor state [:current-description]))
    (fn [state]
      [:div.ui.panel
       [:div.ui.two.column.middle.aligned.grid
        [:div.ui.left.aligned.column
         (when (clojure.string/blank? @current-description)
           [:h4 "Project Description"])]
        [:div.ui.right.aligned.column
         [:div {:on-click (fn [event]
                            (if @editing?
                              (let [saving? (clojure.string/blank? @current-description)]
                                (reset! current-description @draft-description)
                                (if saving?
                                  (create-description! @current-description)
                                  (update-description! @current-description)))
                              (reset! draft-description @current-description))
                            (reset! editing? (not @editing?)))
                :class (cond-> "ui small icon button"
                         (and @editing?
                              (= @current-description @draft-description ))
                         (str " disabled"))}
          (if @editing?
            [:p "Save"]
            [:i {:class "ui blue pencil horizontal icon"}])]
         (when (and @editing?
                    (not (clojure.string/blank? @current-description)))
           [:div {:on-click (fn [event]
                              (reset! editing? false))
                  :class "ui small icon button"}
            [:p "Discard Changes"]])]
        (if @editing?
          [:div {:class "sixteen wide column"}
           [:form.ui.form
            [TextArea {:fluid "true"
                       :autoHeight true
                       :placeholder "Enter a Markdown description"
                       :on-change (fn [e]
                                    (let [value (-> ($ e :target)
                                                    ($ :value))]
                                      (reset! draft-description
                                              (-> ($ e :target)
                                                  ($ :value)))))
                       :default-value @draft-description}]]
           [:div
            [RenderMarkdown @draft-description]]]
          [:div [RenderMarkdown @current-description]
           [:br]])]])))
