(ns sysrev.test.e2e.labels
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [etaoin.api :as ea]
   [sysrev.test.e2e.core :as e]
   [sysrev.test.xpath :as xpath]))

(def add-label-button
  {"annotation" xpath/add-annotation-label-button
   "boolean" xpath/add-boolean-label-button
   "categorical" xpath/add-categorical-label-button
   "string" xpath/add-string-label-button})

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
      (ea/wait-visible (field-path "short-label"))
      (ea/fill (field-path "short-label") short-label)
      (set-checkbox-button (field-path "required") required)
      (set-checkbox-button (field-path "consensus") consensus)
      (ea/fill (field-path "question") question)
      (ea/fill (field-path "all-values") (str/join "," all-values)))
    (ea/wait-predicate #(= (ea/get-element-value driver (field-path "all-values"))
                           (str/join "," all-values)))))

(defn define-label
  "Create a new label definition using browser interface."
  [{:keys [driver system]} project-id {:keys [value-type] :as label-map}]
  (log/info "creating label definition")
  (doto driver
    (ea/go (e/absolute-url system (str "/p/" project-id "/labels/edit")))
    (e/click-visible (add-label-button value-type))
    (ea/wait-visible (str "//form[" (xpath/contains-class "define-label") "]"))
    (set-label-definition (str "//form[" (xpath/contains-class "define-label") "]") label-map)
    (e/click-visible xpath/save-button)
    e/wait-until-loading-completes))
