(ns sysrev.fda-drugs.interface
  (:require [sysrev.fda-drugs.core :as core])
  (:import (java.nio.file Path)))

(defn applications
  "Takes the data returned by parse-data and returns a map of
  {ApplNo application-map} with data from other files (ApplicationDocs,
  Products, Submissions, etc.) included in the relevant parent maps. E.g.,
  each application has :Products and :Submissions keys, and each submission has
  a :SubmissionDocs key."
  [data]
  (core/applications data))

(defn download-data!
  "Downloads a zip file containing the FDA@Drugs data to path."
  [^Path path]
  (core/download-data! path))

(defn parse-applications
  "A composition of applications and parse-data."
  [^Path path]
  (core/parse-applications path))

(defn parse-data
  "Parses a zip file containing the FDA@Drugs data and returns a map. The
  contents of the file are described at
  https://www.fda.gov/drugs/drug-approvals-and-databases/drugsfda-data-files"
  [^Path path]
  (core/parse-data path))

(defn parse-review-html
  "Parses an HTML review file for links to review documnt PDFs.

  Returns a seq of {:label \"\" :url \"\"} maps. URLs may be relative or absolute."
  [^String html]
  (core/parse-review-html html))
