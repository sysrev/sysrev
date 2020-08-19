(ns sysrev.views.semantic
  (:require ["fomantic-ui"]
            ["semantic-ui-react" :as S]
            [reagent.core :as r]))

;; from https://gist.github.com/TimoKramer/7e93758afb81dcad985fafccc613153a
(defn- component
  "Get a component from sematic-ui-react:
    (component \"Button\")
    (component \"Menu\" \"Item\")"
  [sui-class & ks]
  (r/adapt-react-class
   (if (seq ks)
     (apply goog.object/getValueByKeys sui-class ks)
     sui-class)))

;; segment
(def Segment (component S/Segment))
(def SegmentGroup (component S/Segment "Group"))
;; general

(def Header (component S/Header))
(def Icon (component S/Icon))
(def Loader (component S/Loader))
(def Dimmer (component S/Dimmer))
(def Image (component S/Image))
(def Divider (component S/Divider))

;; form
(def Form (component S/Form))
(def FormButton (component S/Form "Button"))
(def FormField (component S/Form "Field"))
(def FormGroup (component S/Form "Group"))
(def FormInput (component S/Form "Input"))
(def FormRadio (component S/Form "Radio"))
(def TextArea (component S/TextArea))
(def Checkbox (component S/Checkbox))

;; input
(def Input (component S/Input))
;; components
(def Label (component S/Label))
(def LabelDetail (component S/Label "Detail"))
(def Button (component S/Button))
(def Dropdown (component S/Dropdown))
(def DropdownMenu (component S/Dropdown.Menu))
(def DropdownItem (component S/Dropdown.Item))
(def Message (component S/Message))
(def MessageHeader (component S/Message "Header"))
(def Radio (component S/Radio))
(def Select (component S/Select))

;; grid
(def Grid (component S/Grid))
(def Row (component S/Grid "Row"))
(def Column (component S/Grid "Column"))

;; list
(def ListUI (component S/List))
(def ListItem (component S/List "Item"))
(def ListIcon (component S/List "Icon"))
(def ListContent (component S/List "Content"))

;; popup
(def Popup (component S/Popup))
(def PopupHeader (component S/Popup "Header"))

;; modal
(def Modal (component S/Modal))
(def ModalHeader (component S/Modal "Header"))
(def ModalContent (component S/Modal "Content"))
(def ModalDescription (component S/Modal "Description"))

;; menu
(def Menu (component S/Menu))
(def MenuItem (component S/Menu "Item"))

;; tables
(def Table (component S/Table))
(def TableHeader (component S/Table "Header"))
(def TableHeaderCell (component S/Table "HeaderCell"))
(def TableRow (component S/Table "Row"))
(def TableBody (component S/Table "Body"))
(def TableCell (component S/Table "Cell"))

;; search
(def Search (component S/Search))
(def SearchResults (component S/Search "Results"))

;; pagination
(def Pagination (component S/Pagination))

;; tab
(def Tab (component S/Tab))

;; accordion
(def Accordion (component S/Accordion))
(def AccordionContent (component S/Accordion "Content"))
(def AccordionTitle (component S/Accordion "Title"))

