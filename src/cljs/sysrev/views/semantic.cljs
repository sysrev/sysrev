(ns sysrev.views.semantic
  (:require [cljsjs.semantic-ui-react]
            [reagent.core :as r])
  (:require-macros [reagent.interop :refer [$]]))

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
(def Image (adapt :Image))
(def Divider (adapt :Divider))

;; form
(def Form (adapt :Form))
(def FormButton (adapt :Form #($ % :Button)))
(def FormField (adapt :Form #($ % :Field)))
(def FormGroup (adapt :Form #($ % :Group)))
(def FormInput (adapt :Form #($ % :Input)))
(def FormRadio (adapt :Form #($ % :Radio)))
(def TextArea (adapt :TextArea))

;; input
(def Input (adapt :Input))
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

;; modal
(def Modal (adapt :Modal))
(def ModalHeader (adapt :Modal #($ % :Header)))
(def ModalContent (adapt :Modal #($ % :Content)))
(def ModalDescription (adapt :Modal #($ % :Description)))

;; menu
(def Menu (adapt :Menu))
(def MenuItem (adapt :Menu #($ % :Item)))
