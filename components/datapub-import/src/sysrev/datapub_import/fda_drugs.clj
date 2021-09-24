(ns sysrev.datapub-import.fda-drugs
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [sysrev.datapub-client.interface :as dpc]
            [sysrev.fda-drugs.interface :as fda-drugs]
            [sysrev.file-util.interface :as file-util])
  (:import (java.util Base64)
           (org.apache.commons.io IOUtils)))

#_:clj-kondo/ignore
(defn get-applications! []
  (file-util/with-temp-file [path {:prefix "sysrev.datapub-import.fda-drugs-"
                                   :suffix ".zip"}]
    (fda-drugs/download-data! path)
    (fda-drugs/parse-applications path)))

(defn docs-with-applications
  "Returns each ApplicationDoc merged with its application data.

  The :Submissions are reordered such that the first submission always
  corresponds to the ApplicationDoc."
  [apps]
  (->> apps
       (mapcat
        (fn [[ApplNo {:keys [Submissions] :as app}]]
          (let [app* (-> (dissoc app :Submissions)
                         (assoc :ApplNo ApplNo))
                subs (mapv #(dissoc % :Docs) Submissions)]
            (->> Submissions
                 (mapcat
                  (fn [{:keys [Docs] :as sub}]
                    (let [sub* (dissoc sub :Docs)]
                      (->> Docs
                           (remove nil?)
                           (map
                            (fn [doc]
                              (-> (merge doc app*)
                                  (assoc :Submissions
                                         (into [sub*] (remove #{sub*} subs))))))))))))))))

(defn upload-doc! [{:keys [ApplicationDocsURL] :as doc}
                   {:keys [auth-token dataset-id endpoint]}]
  (dpc/create-dataset-entity!
   {:content (->> (http/get ApplicationDocsURL {:as :stream})
                  :body
                  IOUtils/toByteArray
                  (.encodeToString (Base64/getEncoder)))
    :datasetId dataset-id
    :externalId ApplicationDocsURL
    :mediaType "application/pdf"
    :metadata (json/generate-string
               (dissoc doc :ApplicationDocsURL))}
   #{:id}
   :auth-token auth-token
   :endpoint endpoint))

(defn import-fda-drugs-docs! [opts]
  (doseq [doc (docs-with-applications (get-applications!))]
    (try
      (log/info (str "FDA@Drugs upload successful: "
                     (pr-str (upload-doc! doc opts))))
      (catch Exception e
        (log/error
         (str "FDA@Drugs doc upload failed for \"" (:ApplicationDocsURL doc)
              "\": " (.getMessage e)))))))
