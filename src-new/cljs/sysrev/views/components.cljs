(ns sysrev.views.components
  (:require
   [clojure.spec.alpha :as s]
   [sysrev.shared.util :refer [num-to-english]]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [reagent.core :as r]))

(defn labeled-input [label-text input-elt & [attrs label-attrs]]
  (let [attrs (or attrs {})
        label-attrs (or label-attrs {})]
    [:div.ui.labeled.input attrs
     [:div.ui.label label-attrs label-text]
     input-elt]))

;;;
;;; primary-tabbed-menu
;;;

(s/def ::tab-id keyword?)
(s/def ::content any?)
(s/def ::action (or string? fn?))
(s/def ::menu-tab
  (s/keys :req-un [::tab-id ::content ::action]))

(defn primary-tabbed-menu [entries active-tab-id & [menu-class]]
  (let [menu-class (or menu-class "")
        entries (remove nil? entries)
        n-tabs (count entries)
        n-tabs-word (num-to-english n-tabs)]
    [:div.ui
     {:class
      (str n-tabs-word " item secondary pointing menu tabbed-menu " menu-class)}
     (doall
      (map-indexed
       (fn [i {:keys [tab-id action content]}]
         (let [attrs {:key i
                      :class (if (= tab-id active-tab-id)
                               "active item" "item")}]
           [:a
            (merge attrs (if (string? action)
                           {:href action} {:on-click action}))
            (if (string? content)
              [:h4.ui.header content] content)]))
       entries))]))
(s/fdef
 primary-tabbed-menu
 :args (s/cat :entries (s/coll-of ::menu-tab)
              :active-tab-id ::tab-id
              :menu-class (s/? string?)))

;;;

(defn selection-dropdown [selected-item items]
  (r/create-class
   {:component-did-mount
    #(-> (js/$ (r/dom-node %))
         (.dropdown))
    :reagent-render
    (fn [selected-item items]
      [:div.ui.selection.dropdown
       [:i.dropdown.icon]
       selected-item
       (into [:div.menu] items)])}))
