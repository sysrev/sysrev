(ns sysrev.views.components.brat
  (:require [goog.string :as gstr]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
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


(defn generate-save-data [^js data]
  (let [save-data #js{}] ; This bit builds a new js object with just the fields needed to repopulate the annotator
    (set! (.-text save-data) (-> data .-sourceData .-text))
    (set! (.-entities save-data) (-> data .-sourceData .-entities))
    (set! (.-relations save-data) (-> data .-sourceData .-relations))
    save-data))

(defn save-doc [^js data article-id]
  (let [labels (vals @(subscribe [:project/labels-raw]))
        relationship-label (first (filter #(= (:value-type %) "relationship") labels))
        save-data (generate-save-data data)]
    (dispatch [:review/set-label-value
                          article-id "na" (:global-label-id relationship-label) "na" (.stringify js/JSON save-data)])))

(defn generate-arcs [relationships]
  (map
    (fn [relation]
      #js{:color "black"
          :arrowHead "triangle,5"
          :labels #js[(:value relation)]
          :type (:value relation)
          :targets #js[(gstr/trim (:to relation))]})
    relationships))

(defn generate-entity-types [labels]
  (map
    (fn [label]
      (let [relationships (-> labels :definition :relationships)
            entity-relations (filter #(= (gstr/trim (:from %)) (gstr/trim label)) relationships)
            arcs (into [] (generate-arcs entity-relations))]
        #js{:bgColor "#ffccaa"
                              :attributes  #js[]
                              :children #js[]
                              :type (gstr/trim label)
                              :fgColor "black"
                              :borderColor "darken",
                              :normalizations #js[],
                              :name (gstr/trim label)
                              :arcs (clj->js arcs)}))
    (:all-values (:definition labels))))

(defn generate-event-types [relationship-label]
  (map
    (fn [label]
      (let [entity-relations (filter #(= (gstr/trim (:from %)) (gstr/trim label)) (-> relationship-label :definition :relationships))
            arcs (into [] (generate-arcs entity-relations))]
        #js{:borderColor "darken"
            :normalizations #js[]
            :name (gstr/trim label)
            :type (gstr/trim label)
            :labels nil
            :unused true
            :bgColor "lightgreen"
            :attributes #js[]
            :fgColor "black"
            :children #js[#js{:borderColor "darken"
                              :normalizations #js[]
                              :name (gstr/trim label)
                              :type (gstr/trim label),
                              :fgColor "black"
                              :children #js[]
                              :arcs (clj->js arcs)}]}))
    (-> relationship-label :definition :event-types)))
      ; {
      ;     "borderColor": "darken",
      ;     "normalizations": [],
      ;     "name": "Be born",}})
;; leaving here in case we need - this doesn't seem to control
;; relationships so Im not sure why it's there
; (defn generate-relation-types [relationships]
;   (map
;     (fn [relation]
;       #js{:args #js[
;                     #js{:role "Arg1"
;                         :targets #js[(gstr/trim (:from relation))]}
;                     #js{:role "Arg2"
;                         :targets #js[(gstr/trim (:to relation))]}]
;           :arrowHead "triangle,5"
;           :name (gstr/trim (:value relation))
;           :color "black"
;           :labels nil
;           :children #js[]
;           :unused false
;           :attributes #js[]
;           :type (gstr/trim (:value relation))
;           :properties #js{}})
;     relationships))


(defn generate-collection-vals [relationship-label]
  (let [entity_types (into [] (generate-entity-types relationship-label))
        event_types (into [] (generate-event-types relationship-label))]
    #js{:action "getCollectionInformation"
        :entity_types (clj->js entity_types)
        :messages #js[]
        :event_types (clj->js event_types)
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
                  :text (:text opts "")
                  :entities (or (:entities opts) [])
                  :relations (or (:relations opts) []))))

(defn ajax-callback [opts-atom relationship-labels article-id request]
  (let [data (.-data request)
        action (.-action data)
        success #(.success request %)]
    (case action
      "getCollectionInformation" (success (generate-collection-vals relationship-labels))
      "createSpan" (success (create-span-response data))
      "getDocument" (success (get-doc-response @opts-atom))
      "loadConf" (success loadConf-response)
      "logout" nil
      "saveConf" nil
      "createArc" (success (create-arc-response data))
      (js/console.warn "Unhandled brat action:" action
                       (.-data request)))
    (when (some #(= % action) ["createSpan" "createArc"])
      (save-doc data article-id)))) ; every request we save it back to the local label state

(defn Brat [opts labels article-id]
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
              (partial ajax-callback opts-atom (first relationship-label) article-id)))
      :component-did-update
      (fn [this [_ old-opts]]
        (let [[_ {:keys [text] :as new-opts}] (r/argv this)]
          (reset! opts-atom new-opts)
          (when (not= text (:text old-opts))
            (send-message @iframe {:document (assoc empty-doc :text text)}))))
      :reagent-render
      (fn [_ _ article-id]
        [:iframe {:height 600 :width 800
                  :src (str "/brat/index.xhtml#/article/" article-id)}])})))
