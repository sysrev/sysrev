(ns sysrev.views.semantic
  (:require [cljsjs.semantic-ui-react]
            [reagent.core :as r])
  (:require-macros [reagent.interop :refer [$]]))

;; something like below DOES NOT work for sub-el
;; (defn adapt-semantic-ui
;;   ([el]
;;    (r/adapt-react-class (goog.object/get semantic-ui el)))
;;   ([el sub-el]
;;    (r/adapt-react-class
;;     ($ (goog.object/get semantic-ui el) sub-el))))

(def semantic-ui js/semanticUIReact)

(defn adapt [class & [subclass-fn]]
  (r/adapt-react-class
   (cond-> (goog.object/get semantic-ui (name class))
     subclass-fn (subclass-fn))))

;; general
(def Segment (adapt :Segment))
(def Header (adapt :Header))
(def Icon (adapt :Icon))
(def Loader (adapt :Loader))

;; form
(def Form (adapt :Form))
(def FormButton (adapt :Form #($ % :Button)))
(def FormField (adapt :Form #($ % :Field)))
(def FormGroup (adapt :Form #($ % :Group)))
(def FormInput (adapt :Form #($ % :Input)))
(def FormRadio (adapt :Form #($ % :Radio)))

;; components
(def Label (adapt :Label))
(def Button (adapt :Button))
(def Dropdown (adapt :Dropdown))
(def Message (adapt :Message))
(def MessageHeader (adapt :Message #($ % :Header)))
(def Radio (adapt :Radio))
(def Select (adapt :Select))

;; grid
(def Grid (adapt :Grid))
(def Row (adapt :Grid #($ % :Row)))
(def Column (adapt :Grid #($ % :Column)))

;; list
(def ListUI (adapt :List))
(def ListItem (adapt :List #($ % :Item)))

;; popup
(def Popup (adapt :Popup))
(def PopupHeader (adapt :Popup #($ % :Header)))
