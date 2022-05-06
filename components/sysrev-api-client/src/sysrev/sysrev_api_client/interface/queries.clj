(ns sysrev.sysrev-api-client.interface.queries
  (:require
   [clojure.string :as str]))

;; This namespace should not have any dependencies so that it can easily be
;; included everywhere.

(declare return->string)

(defn create-project
  "Returns the string representation of a createProject mutation.

  The return arg is processed by `return->string`."
  [return]
  (str "mutation($input: CreateProjectInput!){createProject(input: $input){"
       (return->string return)
       "}}"))

(defn create-project-label
  "Returns the string representation of a createProjectLabel mutation.

  The return arg is processed by `return->string`."
  [return]
  (str "mutation($input: CreateProjectLabelInput!){createProjectLabel(input: $input){"
       (return->string return)
       "}}"))

(defn create-project-source
  "Returns the string representation of a createProjectSource mutation.

  The return arg is processed by `return->string`."
  [return]
  (str "mutation($input: CreateProjectSourceInput!){createProjectSource(input: $input){"
       (return->string return)
       "}}"))

(defn get-project
  "Returns the string representation of a getProject query.

  The return arg is processed by `return->string`."
  [return]
  (str "query($id: ID!){getProject(id: $id){"
       (return->string return)
       "}}"))

(defn get-project-label
  "Returns the string representation of a getProjectLabel query.

  The return arg is processed by `return->string`."
  [return]
  (str "query($id: ID!){getProjectLabel(id: $id){"
       (return->string return)
       "}}"))

(defn return->string
  "Returns a string representation of the GraphQL return field names for a
  given string or seq. A string argument is returned unchanged.

  Examples:
  (return->string \"id\") => \"id\"
  (return->string [:id :name]) => \"id name\""
  [return]
  (cond
    (string? return) return
    (seq return) (->> return
                      (keep #(when % (name %)))
                      (str/join \space))
    :else (throw (ex-info "Should be a string or seq." {:value return}))))
