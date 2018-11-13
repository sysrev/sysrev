(ns sysrev.views.semantic
  (:require [cljsjs.semantic-ui-react]
            [reagent.core :as r])
  (:require-macros [reagent.interop :refer [$]]))

(def semantic-ui js/semanticUIReact)
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
