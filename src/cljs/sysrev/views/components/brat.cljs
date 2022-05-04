(ns sysrev.views.components.brat
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(let [now (js/Date.now)]
  (def empty-doc
    {:text ""
     :ctime now
     :triggers []
     :mtime now
     :source_files []
     :messages []
     :relations []
     :entities []
     :comments []
     :attributes []
     :equivs []
     :events []}))

(def loadConf-response
  #js{:action "loadConf"
      :messages #js[]
      :protocol 1})

(defn send-message [elem m]
  (.postMessage (.-contentWindow elem)
                (.stringify js/JSON (clj->js m))))

(defn get-doc-response [opts]
  (clj->js (assoc empty-doc
                  :action "getDocument"
                  :text (:text opts ""))))

(defn ajax-callback [opts-atom request]
  (let [action (.-action (.-data request))
        success #(.success request %)]
    (case action
      "getDocument" (success (get-doc-response @opts-atom))
      "loadConf" (success loadConf-response)
      "logout" nil
      "saveConf" nil
      (js/console.warn "Unhandled brat action:" action))))

(defn Brat [opts]
  (let [iframe (atom nil)
        opts-atom (atom opts)]
    (r/create-class
     {:display-name "Brat"
      :component-did-mount
      (fn [this]
        (reset! iframe (rdom/dom-node this))
        (set! (.-ajaxCallback ^js (.-contentWindow ^js @iframe))
              (partial ajax-callback opts-atom)))
      :component-did-update
      (fn [this [_ old-opts]]
        (let [[_ {:keys [text] :as new-opts}] (r/argv this)]
          (reset! opts-atom new-opts)
          (when (not= text (:text old-opts))
            (send-message @iframe {:document (assoc empty-doc :text text)}))))
      :reagent-render
      (fn []
        [:iframe {:height 600 :width 800
                  :src "/brat/index.xhtml#/doc"}])})))
