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

;; basics
(def Segment (r/adapt-react-class (goog.object/get semantic-ui "Segment")))
(def Header (r/adapt-react-class (goog.object/get semantic-ui "Header")))
(def Icon (r/adapt-react-class (goog.object/get semantic-ui "Icon")))
(def Loader (r/adapt-react-class (goog.object/get semantic-ui "Loader")))

;; forms
(def Form (r/adapt-react-class (goog.object/get semantic-ui "Form")))
(def FormButton (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Button)))
(def FormField (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Field)))
(def FormGroup (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Group)))
(def FormInput (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Input)))
(def FormRadio (r/adapt-react-class
                ($ (goog.object/get semantic-ui "Form") :Radio)))
(def Label (r/adapt-react-class
            (goog.object/get semantic-ui "Label")))
(def Button (r/adapt-react-class
             (goog.object/get semantic-ui "Button")))
(def Dropdown (r/adapt-react-class
               (goog.object/get semantic-ui "Dropdown")))
(def Message (r/adapt-react-class
              (goog.object/get semantic-ui "Message")))
(def MessageHeader (r/adapt-react-class
                    ($ (goog.object/get semantic-ui "Message") :Header)))
(def Radio (r/adapt-react-class
            (goog.object/get semantic-ui "Radio")))
(def Select (r/adapt-react-class
             (goog.object/get semantic-ui "Select")))
(def TextArea (r/adapt-react-class (goog.object/get semantic-ui "TextArea")))
;; grids
(def Grid (r/adapt-react-class
           (goog.object/get semantic-ui "Grid")))
(def Row (r/adapt-react-class
          ($ (goog.object/get semantic-ui "Grid") :Row)))
(def Column (r/adapt-react-class
             ($ (goog.object/get semantic-ui "Grid") :Column)))
(def Divider (r/adapt-react-class (goog.object/get semantic-ui "Divider")))
;; lists
(def ListUI (r/adapt-react-class
             (goog.object/get semantic-ui "List")))
(def Item (r/adapt-react-class
           ($ (goog.object/get semantic-ui "List") :Item)))
