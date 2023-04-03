(ns sysrev.scheduler.gpt
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.scheduler :as q]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule repeat-forever with-interval-in-minutes]]
            [clojurewerkz.quartzite.triggers :as t]
            [sysrev.db.core :as db]
            [sysrev.util :as util]))

(def default-opts
  {:in nil
   :err :inherit
   :out :inherit
   :shutdown p/destroy-tree})

(defn process [& args]
  (let [[maybe-opts & more] args]
    (if (map? maybe-opts)
      (apply p/process (merge default-opts maybe-opts) more)
      (apply p/process args))))

(defn flow-process [sr-context config]
  (let [temp-dir (fs/create-temp-dir)
        config-file (fs/path temp-dir (str "sr.yaml"))
        config (assoc config :sink-all-events true)]
    (with-open [writer (io/writer (fs/file config-file))]
      (yaml/generate-stream writer config))
    {:process (process
               {:dir (str temp-dir)
                :extra-env {"OPENAI_API_KEY" (-> sr-context :config :openai-api-key)
                            "SRVC_TOKEN" (-> sr-context :config :sysrev-dev-key)}}
               "sr" "flow" "gpt4-label")
     :temp-dir temp-dir}))

(defn gpt4-config [sr-context project-id label-name]
  (let [server-url (util/server-url sr-context)]
    {:base-uri (str server-url "/web-api/srvc-config?project-id=" project-id)
     :reviewer "https://github.com/insilica/sfac/tree/master/gpt4-label"
     :flows
     {:gpt4-label
      {:steps
       [{:run-embedded (str "generator " server-url "/web-api/srvc-events?project-id=" project-id)}
        {:uses "github:insilica/sfac/86e9e5a5b813773cddfa36070d8c2fa31d7d4859#gpt4-label"
         :labels [label-name]}]}}}))

(defn get-projects-to-update [sr-context]
  (-> sr-context
      (db/execute!
       {:select-distinct :project.project-id
        :from :project
        :join [:project-source [:= :project.project-id :project-source.project-id]]
        :where [:and
                [:raw "(settings->>'gpt-access')::boolean"]
                [:or
                 [:= :last-gpt-run nil]
                 [:>= :project-source.date-created :project.last-gpt-run]
                 [:and
                  [:not= :project-source.import-date nil]
                  [:>= :project-source.import-date :project.last-gpt-run]]]]})
      (->> (map :project/project-id))))

(defn get-gpt-enabled-labels [sr-context]
  (db/with-tx [sr-context sr-context]
    (let [project-ids (get-projects-to-update sr-context)]
      (when (seq project-ids)
        (-> sr-context
            (db/execute!
             {:select :*
              :from :label
              :where [:and
                      [:= :predict-with-gpt true]
                      [:in :project-id project-ids]]}))))))

; Make sure we don't waste money with concurrent runs
(defonce run-lock (Object.))

(defn run [sr-context]
  (locking run-lock
    (doseq [{:label/keys [project-id]
             label-name :label/name}
            (get-gpt-enabled-labels sr-context)]
      (let [config (gpt4-config sr-context project-id label-name)
            {:keys [process]} (flow-process sr-context config)]
        (db/execute-one!
         sr-context
         {:update :project
          :set {:last-gpt-run [:now]}
          :where [:= :project-id project-id]})
        @process))))

#_:clj-kondo/ignore
(j/defjob Job [ctx]
  (run (get (qc/from-job-data ctx) "sr-context")))

#_:clj-kondo/ignore
(defn job [{:keys [quartz-scheduler sr-context]}]
  (let [job (j/build
             (j/of-type Job)
             (j/using-job-data {:sr-context sr-context})
             (j/with-identity (j/key "jobs.gpt.1")))
        trigger (t/build
                 (t/with-identity (t/key "triggers.gpt.1"))
                 (t/start-now)
                 (t/with-schedule (schedule
                                   (repeat-forever)
                                   (with-interval-in-minutes 1))))]
    (q/schedule quartz-scheduler job trigger)))
