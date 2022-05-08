(ns sysrev.views.components.brat
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [sysrev.util :as util]))

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

(defn create-span-response [^js data]
  (let [source-data ^js (.-sourceData data)
        ;; This is a string for some reason
        offsets (js/JSON.parse (.-offsets data))
        ;; Find the next-highest id
        id (->> source-data .-entities
                (keep (comp #(util/parse-integer %) first))
                (reduce max 0)
                inc str)]
    ;; Add the new span to the existing ones
    (->> source-data .-entities
         (concat #js[#js[id (.-type data) offsets]])
         into-array
         (set! (.-entities source-data)))
    (-> {:action "createSpan"
         :annotations source-data
         :edited #js[#js[id]]
         :messages #js[]
         :protocol 1}
        clj->js)))


(defn create-arc-response [^js data]
  (let [source-data ^js (.-sourceData data)
        id (->> source-data .-relations
                (keep (comp #(util/parse-integer %) first))
                (reduce max 0)
                inc str)]
    (->> source-data .-relations
         (concat #js[#js[id (.-type data) #js[#js["Arg1" (.-origin data)] #js["Arg2" (.-target data)]]]])
         into-array
         (set! (.-relations source-data)))
    (clj->js
      {:action "createArc"
       :annotations source-data
       :edited []
       :messages #js[]
       :protocol 1})))


(defn save-doc [data]
  (print data))
  ; on success
  ; on fail

(defn send-message [elem m]
  (.postMessage (.-contentWindow elem)
                (.stringify js/JSON (clj->js m))))

(defn get-doc-response [opts]
  (clj->js (assoc empty-doc
                  :action "getDocument"
                  :text (:text opts ""))))

(defn ajax-callback [opts-atom request]
  (let [data (.-data request)
        action (.-action data)
        success #(.success request %)]
    (case action
      "createSpan" (success (create-span-response data))
      "getDocument" (success (get-doc-response @opts-atom))
      "loadConf" (success loadConf-response)
      "logout" nil
      "saveConf" nil
      "createArc" (success (create-arc-response data))
      "saveDoc" (save-doc data)
      (js/console.warn "Unhandled brat action:" action
                       (.-data request)))))

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
