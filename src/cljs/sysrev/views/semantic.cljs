(ns sysrev.views.semantic
  (:require [cljsjs.semantic-ui-react]
            [reagent.core :as r])
  (:require-macros [reagent.interop :refer [$]]))

(def semantic-ui js/semanticUIReact)

;; from https://gist.github.com/TimoKramer/7e93758afb81dcad985fafccc613153a
(defn component
  "Get a component from sematic-ui-react:
    (component \"Button\")
    (component \"Menu\" \"Item\")"
  [k & ks]
  (r/adapt-react-class
   (if (seq ks)
     (apply goog.object/getValueByKeys semantic-ui k ks)
     (goog.object/get semantic-ui k))))

;; general
(def Segment (component "Segment"))
(def Header (component "Header"))
(def Icon (component "Icon"))
(def Loader (component "Loader"))
(def Image (component "Image"))
(def Divider (component "Divider"))

;; form
(def Form (component "Form"))
(def FormButton (component "Form" "Button"))
(def FormField (component "Form" "Field"))
(def FormGroup (component "Form" "Group"))
(def FormInput (component "Form" "Input"))
(def FormRadio (component "Form" "Radio"))
(def TextArea (component "TextArea"))
(def Checkbox (component "Checkbox"))

;; input
(def Input (component "Input"))
;; components
(def Label (component "Label"))
(def LabelDetail (component "Label" "Detail"))
(def Button (component "Button"))
(def Dropdown (component "Dropdown"))
(def Message (component "Message"))
(def MessageHeader (component "Message" "Header"))
(def Radio (component "Radio"))
(def Select (component "Select"))

;; grid
(def Grid (component "Grid"))
(def Row (component "Grid" "Row"))
(def Column (component "Grid" "Column"))

;; list
(def ListUI (component "List"))
(def ListItem (component "List" "Item"))
(def ListIcon (component "List" "Icon"))
(def ListContent (component "List" "Content"))

;; popup
(def Popup (component "Popup"))
(def PopupHeader (component "Popup" "Header"))

;; modal
(def Modal (component "Modal"))
(def ModalHeader (component "Modal" "Header"))
(def ModalContent (component "Modal" "Content"))
(def ModalDescription (component "Modal" "Description"))

;; menu
(def Menu (component "Menu"))
(def MenuItem (component "Menu" "Item"))

;; tables
(def Table (component "Table"))
(def TableHeader (component "Table" "Header"))
(def TableHeaderCell (component "Table" "HeaderCell"))
(def TableRow (component "Table" "Row"))
(def TableBody (component "Table" "Body"))
(def TableCell (component "Table" "Cell"))

;; search
(def Search (component "Search"))
(def SearchResults (component "Search" "Results"))

;; pagination
(def Pagination (component "Pagination"))
