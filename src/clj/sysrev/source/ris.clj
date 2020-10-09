(ns sysrev.source.ris
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [sysrev.datasource.api :as ds-api]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.slack :refer [log-slack-custom]]
            [sysrev.util :as util]))

(defmethod make-source-meta :ris [_ {:keys [filename hash]}]
  {:source "RIS file" :filename filename :hash hash})

(defn ris-title [m]
  (let [{:keys [TI T1 BT CT]} m]
    (first (last (sort-by count [TI T1 BT CT])))))

(defn ris-get-articles [coll]
  (->> coll
       (map #(let [{:keys [id]} %
                   primary-title (ris-title %)]
               {:external-id (str id) :primary-title primary-title}))
       (into [])))

(defn make-corrected-ris-file
  "Creates a new temporary RIS file from content of `in-file`,
  attempting to correct for known formats that violate the RIS
  standard and won't be parsable in their original form.

  Returns a File for the new temporary file.

  Currently this removes individual lines in the file that are invalid
  by RIS standard, removes whitespace in otherwise-empty lines, and
  removes consecutive empty lines after the first. It also converts
  line breaks to CRLF-style."
  [in-file]
  (let [out-file (util/create-tempfile :suffix "-corrected.ris")
        last-empty (atom false)
        in-data-raw (slurp in-file)
        ;; change \n\r to \r\n
        ;; (IEEE_Xplore_Citation_Download_2019.11.04.16.41.46.ris)
        in-data (-> in-data-raw (str/replace #"\n\r(?!\n)" "\r\n"))
        ;; split lines using both \r\n and \n
        crlf-lines (str/split in-data #"\r\n")
        unix-lines (str/split in-data #"\n")
        ;; try to guess whether \r\n or \n is the endline delimiter
        crlf-file? (>= (count crlf-lines)
                       (quot (count unix-lines) 2))
        ris-lines (if crlf-file? crlf-lines unix-lines)
        tag-regexp (if crlf-file?
                     #"^([a-zA-Z0-9]{2})  - ([^\r]*)"
                     #"^([a-zA-Z0-9]{2})  - ([^\r\n]*)")]
    (with-open [out (io/writer out-file :append true)]
      (doseq [line ris-lines]
        (cond (empty? (str/trim line))  ; valid empty line?
              (when-not @last-empty  ; skip if previous line was empty
                (.write out "\r\n")
                (reset! last-empty true))
              (re-matches tag-regexp line) ; valid tag line?
              (let [[_ tag content] (re-matches tag-regexp line)]
                (if (and (= tag "ER") (empty? (str/trim content)))
                  ;; write correct "ER" line - end of citation record;
                  ;; remove extraneous whitespace in content
                  (.write out (format "%s  - \r\n" tag))
                  ;; write normal tag line
                  (.write out (format "%s  - %s\r\n" tag content)))
                (reset! last-empty false))
              ;; otherwise exclude this line from output
              :else nil)))
    out-file))

(defmethod import-source :ris [_ project-id {:keys [file filename]} {:as options}]
  (let [filename-sources (->> (source/project-sources project-id)
                              (filter #(= (get-in % [:meta :filename]) filename)))]
    ;; this source already exists
    (if (seq filename-sources)
      (do (log/warn "import-source RIS - non-empty filename-sources: " filename-sources)
          {:error {:message (str filename " already imported")}})
      ;; attempt to create a RIS citation
      (let [{:keys [status] :as _response}
            (ds-api/create-ris-file {:file file :filename filename})
            {:keys [status body] :as _response}
            (if (= status 200)
              _response
              (do (log/warnf "import-source :ris - parsing failed for original file\n%s"
                             (select-keys _response [:status :body]))
                  (ds-api/create-ris-file {:file (make-corrected-ris-file file)
                                           :filename filename})))
            {:keys [hash error #_ reason]} body]
        (if (not= status 200)
          (do (log-slack-custom [(format "*RIS file import failed*:\n```%s```"
                                         (util/pp-str {:project-id project-id
                                                       :filename filename
                                                       :file (str file)
                                                       :response body}))]
                                "RIS file import failed")
              {:error {:message error}})
          (let [source-meta (source/make-source-meta :ris {:filename filename :hash hash})]
            (import-source-impl
             project-id source-meta
             {:types {:article-type "academic" :article-subtype "RIS"}
              :get-article-refs #(ds-api/fetch-ris-articles-by-hash hash)
              :get-articles ris-get-articles}
             options)
            {:result true}))))))
