(ns sysrev.sysrev-api-client.interface.queries
  (:require
   [clojure.string :as str]))

;; This namespace should not have any dependencies so that it can easily be
;; included everywhere.

(declare return->string)

(defn m-create-project
  "Returns the string representation of a createProject mutation.

  The return arg is processed by `return->string`."
  [return]
  (str "mutation($input: CreateProjectInput!){createProject(input: $input){"
       (return->string return)
       "}}"))

(defn q-project
  "Returns the string representation of a project query.

  The return arg is processed by `return->string`."
  [return]
  (str "query($id: ID!){project(id: $id){"
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
