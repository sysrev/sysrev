(ns sysrev.views.components.brat
  (:require [reagent.core :as r]
            [goog.string :as gstring]
            [reagent.dom :as rdom]
            [sysrev.util :as util]
            [sysrev.action.core :refer [def-action]]
            [re-frame.core :refer [dispatch]]))

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


(def-action :save-brat-annotation
  :uri (constantly "/api/set-labels")
  :content (fn [project-id {:keys [article-id label-values change? confirm? resolve?]}]
             {:project-id project-id
              :article-id article-id
              :label-values label-values
              :confirm? (boolean confirm?)
              :resolve? (boolean resolve?)
              :change? (boolean change?)})
   :process
      (fn [_ [_ {:keys [on-success]}] _]
        (when on-success
          (let [success-fns    (->> on-success (remove nil?) (filter fn?))
                success-events (->> on-success (remove nil?) (remove fn?))]
            (doseq [f success-fns] (f))
            {:dispatch-n (concat success-events [[:review/reset-saving]
                                                 [:review/reset-ui-labels]])}))))
; [:action [:org/get-share-code org-id]]
(defn save-doc [data]
  (dispatch
   [:action
    [:save-brat-annotation
     1200001
     {
      :article-id 36900004
      :label-values {#uuid "c4f5481e-6008-4011-8210-ce6d3bc0f1e8" (.stringify js/JSON data)}
      :confirm? true
      :resolve? true}]]))

(defn generate-arcs [relationships]
  (map
    (fn [relation]
      #js{:color "black"
          :arrowHead "triangle,5"
          :labels #js[(:value relation)]
          :type (:value relation)
          :targets #js[(gstring/trim (:to relation))]})
    relationships))

(defn generate-entity-types [labels]
  (map
    (fn [label]
      (let [relationships (-> labels :definition :relationships)
            entity-relations (filter #(= (gstring/trim (:from %)) (gstring/trim label)) relationships)
            arcs (into [] (generate-arcs entity-relations))]
        (print arcs)
        #js{:bgColor "#ffccaa"
                              :attributes  #js[]
                              :children #js[]
                              :type (gstring/trim label)
                              :fgColor "black"
                              :borderColor "darken",
                              :normalizations #js[],
                              :name (gstring/trim label)
                              :arcs (clj->js arcs)}))
    (:all-values (:definition labels))))

;; leaving here in case we need - this doesn't seem to control
;; relationships so Im not sure why it's there
; (defn generate-relation-types [relationships]
;   (map
;     (fn [relation]
;       #js{:args #js[
;                     #js{:role "Arg1"
;                         :targets #js[(gstring/trim (:from relation))]}
;                     #js{:role "Arg2"
;                         :targets #js[(gstring/trim (:to relation))]}]
;           :arrowHead "triangle,5"
;           :name (gstring/trim (:value relation))
;           :color "black"
;           :labels nil
;           :children #js[]
;           :unused false
;           :attributes #js[]
;           :type (gstring/trim (:value relation))
;           :properties #js{}})
;     relationships))


(defn generate-collection-vals [relationship-label]
  (let [entity_types (into [] (generate-entity-types relationship-label))]
    #js{:action "getCollectionInformation"
        :entity_types (clj->js entity_types)
        :messages #js[]
        :event_types #js[]
        :items #js[]
        :disambiguator_config #js[]
        :unconfigured_types #js[]
        :relation_attribute_types #js[]
        :search_config #js[
                           #js["Google" "http://www.google.com/search?q=%s"]
                           #js["Wikipedia" "http://en.wikipedia.org/wiki/Special:Search?search=%s"]]


        :ui_names #js{
                       :entities "entities",
                       :events "events",
                       :relations "relations",
                       :attributes "attributes"}}))

  ; on success
  ; on fail

(defn send-message [elem m]
  (.postMessage (.-contentWindow elem)
                (.stringify js/JSON (clj->js m))))

(defn get-doc-response [opts]
  (clj->js (assoc empty-doc
                  :action "getDocument"
                  :text (:text opts ""))))

(defn ajax-callback [opts-atom relationship-labels request]
  (let [data (.-data request)
        action (.-action data)
        success #(.success request %)]
    (js/console.log request)
    (case action
      "getCollectionInformation" (success (generate-collection-vals relationship-labels))
      "createSpan" (success (create-span-response data))
      "getDocument" (success (get-doc-response @opts-atom))
      "loadConf" (success loadConf-response)
      "logout" nil
      "saveConf" nil
      "createArc" (success (create-arc-response data))
      "saveDoc" (save-doc data)
      (js/console.warn "Unhandled brat action:" action
                       (.-data request)))))

(defn Brat [opts labels]
  (let [iframe (atom nil)
        opts-atom (atom opts)
        relationship-label (filter #(= (:value-type %) "relationship") labels)]
    (r/create-class
     {:display-name "Brat"
      :component-did-mount
      (fn [this]
        (reset! iframe (rdom/dom-node this))
        (set! (.-ajaxCallback ^js (.-contentWindow ^js @iframe))
              ; TODO the second here is just for testing fix
              (partial ajax-callback opts-atom (first relationship-label))))
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
