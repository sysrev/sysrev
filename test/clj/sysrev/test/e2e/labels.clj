(ns sysrev.test.e2e.labels
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [etaoin.api :as ea]
   [sysrev.etaoin-test.interface :as et]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.xpath :as xpath]
   [sysrev.util :as util]))

(def add-label-button
  {"annotation" xpath/add-annotation-label-button
   "boolean" xpath/add-boolean-label-button
   "categorical" xpath/add-categorical-label-button
   "string" xpath/add-string-label-button})

(def include-label-definition {:value-type "boolean"
                               :short-label "Include"
                               :required true})

(defn set-checkbox-button
  "When selected? is true, set checkbox defined by q to 'on',
  otherwise if selected? is false, set checkbox button to 'off'"
  [driver q selected?]
  (when-not (= (boolean selected?) (ea/selected? driver q))
    (ea/click driver q)))

(defmulti set-label-definition
  "Set definition for label using browser interface."
  (fn [driver xpath {:keys [value-type]}]
    (keyword value-type)))

(defmethod set-label-definition :annotation
  [driver
   xpath
   {:keys [question short-label required consensus definition]
    :or {question "" short-label "" required false}} & group?]
  (let [{:keys [all-values]} definition
        xpath (if group?
                "//h5[contains(@class,'value-type') and contains(text(),'Annotation Label')]"
                xpath)
        field-path #(xpath/field-input-xpath xpath (str "field-" %))]
    (doto driver
      (et/fill-visible (field-path "short-label") short-label)
      (set-checkbox-button (field-path "required") required)
      (set-checkbox-button (field-path "consensus") consensus)
      (et/fill-visible (field-path "question") question)
      (et/fill-visible (field-path "all-values") (str/join "," all-values)))
    (ea/wait-predicate #(= (ea/get-element-value driver (field-path "all-values"))
                           (str/join "," all-values)))))

(defn define-label
  "Create a new label definition using browser interface."
  [{:keys [driver system]} project-id {:keys [value-type] :as label-map}]
  (log/info "creating label definition")
  (doto driver
    (ea/go (e/absolute-url system (str "/p/" project-id "/labels/edit")))
    (et/click-visible (add-label-button value-type))
    (ea/wait-visible (str "//form[" (xpath/contains-class "define-label") "]"))
    (set-label-definition (str "//form[" (xpath/contains-class "define-label") "]") label-map)
    (et/click-visible xpath/save-button)
    e/wait-until-loading-completes))

(defn label-column-xpath [& {:keys [label-id short-label]}]
  (util/assert-single label-id short-label)
  (str "//div[contains(@class,'label-edit') and contains(@class,'column') and "
       (cond label-id    (format "@data-label-id='%s'" (str label-id))
             short-label (format "@data-short-label='%s'" short-label))
       "]"))

(defn label-grid-xpath [& {:keys [label-id short-label] :as args}]
  (str (util/apply-keyargs label-column-xpath args)
       "/div[contains(@class,'label-edit') and contains(@class,'grid')]"))

(defmulti set-label-answer!
  "Set answer value for a single label on current article."
  (fn [driver {:keys [value-type]}]
    (keyword value-type)))

(defmethod set-label-answer! :boolean
  [driver {:keys [short-label value]}]
  (et/click-visible driver (str (label-grid-xpath :short-label short-label)
                               "/div[contains(@class,'label-edit-value')]"
                               "//div[contains(@class,'button') and "
                               (format "text()='%s'"
                                       (case value true "Yes" false "No" nil "?"))
                               "]")))
