(ns sysrev.views.semantic
  (:require ["semantic-ui-react" :as S]
            [goog.object]
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
(defonce Segment (component S/Segment))
(defonce SegmentGroup (component S/Segment "Group"))
;; general

(defonce Header (component S/Header))
(defonce Icon (component S/Icon))
(defonce Loader (component S/Loader))
(defonce Dimmer (component S/Dimmer))
(defonce Image (component S/Image))
(defonce Divider (component S/Divider))

;; form
(defonce Form (component S/Form))
(defonce FormButton (component S/Form "Button"))
(defonce FormField (component S/Form "Field"))
(defonce FormGroup (component S/Form "Group"))
(defonce FormInput (component S/Form "Input"))
(defonce FormRadio (component S/Form "Radio"))
(defonce TextArea (component S/TextArea))
(defonce Checkbox (component S/Checkbox))

;; input
(defonce Input (component S/Input))
;; components
(defonce Label (component S/Label))
(defonce LabelDetail (component S/Label "Detail"))
(defonce Button (component S/Button))
(defonce ButtonGroup (component S/Button.Group))
(defonce Dropdown (component S/Dropdown))
(defonce DropdownMenu (component S/Dropdown.Menu))
(defonce DropdownItem (component S/Dropdown.Item))
(defonce DropdownHeader (component S/Dropdown.Header))
(defonce DropdownDivider (component S/Dropdown.Divider))
(defonce Message (component S/Message))
(defonce MessageHeader (component S/Message "Header"))
(defonce Radio (component S/Radio))
(defonce Select (component S/Select))

;; grid
(defonce Grid (component S/Grid))
(defonce Row (component S/Grid "Row"))
(defonce Column (component S/Grid "Column"))

;; list
(defonce ListUI (component S/List))
(defonce ListItem (component S/List "Item"))
(defonce ListIcon (component S/List "Icon"))
(defonce ListContent (component S/List "Content"))

;; popup
(defonce Popup (component S/Popup))
(defonce PopupHeader (component S/Popup "Header"))

;; modal
(defonce Modal (component S/Modal))
(defonce ModalHeader (component S/Modal "Header"))
(defonce ModalContent (component S/Modal "Content"))
(defonce ModalDescription (component S/Modal "Description"))

;; menu
(defonce Menu (component S/Menu))
(defonce MenuItem (component S/Menu "Item"))

;; tables
(defonce Table (component S/Table))
(defonce TableHeader (component S/Table "Header"))
(defonce TableHeaderCell (component S/Table "HeaderCell"))
(defonce TableRow (component S/Table "Row"))
(defonce TableBody (component S/Table "Body"))
(defonce TableCell (component S/Table "Cell"))

;; search
(defonce Search (component S/Search))
(defonce SearchResults (component S/Search "Results"))

;; pagination
(defonce Pagination (component S/Pagination))

;; tab
(defonce Tab (component S/Tab))

;; accordion
(defonce Accordion (component S/Accordion))
(defonce AccordionContent (component S/Accordion "Content"))
(defonce AccordionTitle (component S/Accordion "Title"))

;; visibility
(defonce Visibility (component S/Visibility))
(defonce Sticky (component S/Sticky))
(defonce Rail (component S/Rail))

;; ref
(defonce Ref (component S/Ref))

;; transition / portal
(defonce Portal (component S/Portal))
(defonce Transition (component S/Transition))
(defonce TransitionablePortal (component S/TransitionablePortal))
