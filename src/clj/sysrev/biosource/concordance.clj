(ns sysrev.biosource.concordance
  (:require [clojure.data.json :as json]
            [clojure.set :refer [rename-keys]]
            [clj-http.client :as http]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.biosource.core :refer [api-host]]))

;; https://api.insilica.co/service/run/concordance/concordance
(def concordance-route (str api-host "service/run/concordance-2/concordance"))

(defn project-concordance-inputs [project-id keep-resolved]
  (->> (q/find-article-label
        {:a.project-id project-id :l.value-type "boolean"}
        [:l.label-id :al.user-id :al.article-id :answer], :with [:label :article]
        :where (when-not keep-resolved
                 (q/not-exists [:article-resolve :ars]
                               {:ars.article-id :al.article-id}))
        :filter-valid true :confirmed true)
       (map #(update % :answer str))
       (map #(rename-keys % {:article-id :article, :answer :value}))))

(defn project-concordance
  "Given a project, return a vector of user-user-label concordances"
  [project-id & {:keys [keep-resolved] :or {keep-resolved true}}]
  (db/with-project-cache project-id [:concordance keep-resolved]
    (let [input (project-concordance-inputs project-id keep-resolved)
          {:keys [body]} (http/post concordance-route
                                    {:content-type "application/json"
                                     :body (json/write-str input)})]
      (json/read-str body :key-fn keyword))))
