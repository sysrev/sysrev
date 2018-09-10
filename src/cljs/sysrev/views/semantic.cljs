(ns sysrev.views.semantic
  (:require [cljsjs.semantic-ui-react]))

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
