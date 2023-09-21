(ns sysrev.openai-helpers.interface
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sysrev.json.interface :as json]
            [sysrev.util-lite.interface :as ul]
            [tolkien.core :as tolkien]
            [wkok.openai-clojure.api :as openai]))

(defn- not-found? [e]
  (boolean
   (some-> e
           ex-message
           (str/includes? "status: 404"))))

(defmacro retry [& body]
  `(ul/retry
    {:interval-ms 10000
     :n 5
     :throw-pred not-found?}
    ~@body))

(defn create-file [file purpose]
  (-> {:file file :purpose purpose}
      openai/create-file
      retry))

(defn get-file [file-id]
  (-> {:file-id file-id}
      openai/retrieve-file
      retry))

(defn wait-for-file [file-id]
  (let [{:as file :keys [status]} (get-file file-id)]
    (case status
      "error" (throw (ex-info (str "Error processing file " file-id) {:file file}))
      "processed" file
      (do
        (Thread/sleep 5000)
        (recur file-id)))))

(defn create-fine-tuning-job [file]
  (->> (create-file file "fine-tuning")
       :id
       wait-for-file
       :id
       (hash-map :model "gpt-3.5-turbo" :training_file)
       openai/create-fine-tuning-job
       retry))

(defn get-fine-tuning-job [ftjob-id]
  (-> {:fine-tuning-job-id ftjob-id}
      openai/retrieve-fine-tuning-job
      retry))

(defn wait-for-fine-tuning-job [ftjob-id]
  (let [{:as fine-tuning-job :keys [status]} (get-fine-tuning-job ftjob-id)]
    (case status
      "cancelled" (throw (ex-info (str "Fine-tuning job cancelled " ftjob-id) {:fine-tuning-job fine-tuning-job}))
      "failed" (throw (ex-info (str "Fine-tuning job failed " ftjob-id) {:fine-tuning-job fine-tuning-job}))
      "succeeded" fine-tuning-job
      (do
        (Thread/sleep 5000)
        (recur ftjob-id)))))

(defn append-to-prompt [{:as payload :keys [messages]} append-at-end]
  (->> (update (peek messages) :content str append-at-end)
       (conj (pop messages))
       (assoc payload :messages)))

(defn truncate-chat-completion-payload [{:as payload :keys [messages]} max-tokens]
  (let [ct (tolkien/count-chat-completion-tokens payload)]
    (if (<= ct max-tokens)
      payload
      (let [{:as last-msg :keys [content]} (peek messages)]
        (if-not (seq content)
          (throw (ex-info "Cannot truncate further" {:messages messages}))
          (let [new-len (->> (/ ct max-tokens)
                             (/ (count content))
                             Math/ceil
                             (max 1)
                             dec)
                s (subs content 0 new-len)
                new-payload (-> messages pop
                                (conj (assoc last-msg :content s))
                                (->> (assoc payload :messages)))]
            (truncate-chat-completion-payload new-payload max-tokens)))))))

(defn get-model-test-result-seq [model test-file & {:keys [append-at-end max-prompt-tokens]}]
  (for [{:as json :keys [messages]}
        #__ (with-open [rdr (io/reader test-file)]
              (->> rdr line-seq doall
                   (map #(json/read-str % :key-fn keyword))))
        :let [prompt (update json :messages pop)
              payload (-> {:messages (:messages prompt) :model "gpt-3.5-turbo"}
                          (truncate-chat-completion-payload (- max-prompt-tokens (count append-at-end)))
                          (append-to-prompt append-at-end))]]
    {:prompt (dissoc payload :model)
     :expected (peek messages)
     :completion (try
                   (-> (assoc payload :model model)
                       openai/create-chat-completion
                       retry)
                   (catch Exception e
                     e))}))
