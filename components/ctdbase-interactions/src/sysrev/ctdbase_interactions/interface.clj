(ns sysrev.ctdbase-interactions.interface
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [lambdaisland.deep-diff2 :as dd2]
            [sysrev.file-util.interface :as file-util]
            [sysrev.openai-helpers.interface :as openai-helpers]
            [sysrev.pubmed-api.interface :as pubmed-api]
            [tech.v3.dataset :as ds]
            [tech.v3.libs.parquet :as parquet]))

(defn load-chem-gene-ixns [brick-dir]
  (-> (str brick-dir "/CTD_chem_gene_ixns.parquet")
      io/file
      parquet/parquet->ds))

(defn get-ixns-by-pmid
  "Returns a map of pmid to vector of chem-gene interactions"
  [chem-gene-ixns]
  (-> chem-gene-ixns
      (ds/select-columns ["ChemicalName" "GeneSymbol" "GeneForms" "Organism" "Interaction" "InteractionActions" "PubMedIDs"])
      (ds/row-map (fn [{:strs [PubMedIDs]}]
                     {"PubMedIDs" (some-> PubMedIDs (str/split #"\|"))}))
      (ds/unroll-column "PubMedIDs")
      (ds/sort-by-column "PubMedIDs")
      ds/rows
      (->> (group-by #(get % "PubMedIDs")))))

(defn get-training-examples [pubmed-api-opts ixns-by-pmid pmid-cache]
  (->> (reduce dissoc ixns-by-pmid @pmid-cache)
       (keep
        (fn [[pmid rows]]
          (let [[article] (pubmed-api/get-fetches pubmed-api-opts [pmid])
                pmcid (pubmed-api/article-pmcid article)
                tgz-link (some->> pmcid
                                  (pubmed-api/get-pmc-links pubmed-api-opts)
                                  (filter (comp #{"tgz"} :format))
                                  first)]
            (when tgz-link
              (file-util/with-temp-file [temp-file {:prefix "sysrev.ctdbase-brick-" :suffix ".tar.gz"}]
                (pubmed-api/download-pmc-file (:href tgz-link) (str (fs/file temp-file)))
                (let [title (pubmed-api/article-title article)
                      abstract (pubmed-api/article-abstract article)
                      full-text (->> (pubmed-api/get-pmc-file-nxml (fs/file temp-file))
                                     pubmed-api/nxml-text)
                      article-data (-> {:title title :abstract abstract :full-text full-text}
                                       json/write-str)
                      response (->> rows
                                    (mapv #(dissoc % "PubMedIDs"))
                                    json/write-str)]
                  (prn {:pmid pmid :ixns (count rows) :full-text (count full-text)})
                  (swap! pmid-cache conj pmid)
                  {:messages
                   [{:role "system"
                     :content "InsilicaGPT is a research assistant that always answers in JSON"}
                    {:role "user"
                     :content (str article-data "\n\nList the chemical-gene interactions as a JSON array of objects")}
                    {:role "assistant" :content response}]}))))))))

(defn interactions-summary [ixns]
  {:chemical-names (set (map :ChemicalName ixns))
   :count (count ixns)
   :gene-forms (set (map :GeneSymbol ixns))
   :gene-symbols (set (map :GeneSymbol ixns))
   :interactions (set (map :Interaction ixns))
   :interaction-actions (set (map :InteractionActions ixns))
   :organisms (set (map :Organism ixns))})

(defn compare-test-interactions [{:keys [completion expected]}]
  (let [content (get-in completion [:choices 0 :message :content])
        completion (set (try (json/read-str content :key-fn keyword)
                             (catch Exception _
                               nil)))
        expected (-> expected :content (json/read-str :key-fn keyword) set)]
    (->> (interactions-summary completion)
         (dd2/diff (interactions-summary expected))
         dd2/pretty-print)))

(comment
  ;; Setup
  (do
    (def brick-dir "/home/john/src/biobricks-ctdbase/biobricks-ctdbase/brick")
    (def chem-gene-ixns (load-chem-gene-ixns brick-dir))
    (require 'sysrev.main)
    (def memcached (-> @sysrev.main/system :donut.system/instances :sysrev :memcached))
    (def pubmed-api-opts {:api-key nil :memcached memcached}))

  ;; Make training data for chem-gene interactions
  (def ixns-by-pmid (-> chem-gene-ixns get-ixns-by-pmid))
  (def pmid-cache (atom #{}))
  (with-open [w (io/writer "components/ctdbase-interactions/data/chem-gene-ixn-examples.jsonl" :append true)]
    (loop []
      (let [$ (try
                (doseq [example (get-training-examples pubmed-api-opts ixns-by-pmid pmid-cache)]
                  (json/write example w)
                  (.write w "\n"))
                (catch java.net.SocketTimeoutException _
                  (println "SocketTimeoutException, retrying")
                  (Thread/sleep 5000)
                  :recur))]
        (when (= :recur $)
          (recur)))))

  ;; Sample row
  (-> chem-gene-ixns ds/rows first)

  ;; Find a pmcid
  (-> chem-gene-ixns
      ds/rows
      first
      (get "PubMedIDs")
      vector
      (->> (pubmed-api/get-fetches pubmed-api-opts)
           first
           pubmed-api/article-pmcid))

  ;; Get some sample pubmed central links
  (->> chem-gene-ixns
       ds/rows
       (mapcat #(some-> (get % "PubMedIDs") (str/split #"\|")))
       distinct
       (pubmed-api/get-fetches pubmed-api-opts)
       (keep pubmed-api/article-pmcid)
       (take 10)
       (mapcat (partial pubmed-api/get-pmc-links pubmed-api-opts)))

  ;; Download a file from pubmed central and get the article text
  (def temp-file (babashka.fs/create-temp-file))
  (pubmed-api/download-pmc-file "ftp://ftp.ncbi.nlm.nih.gov/pub/pmc/oa_package/fc/a8/PMC4741700.tar.gz" temp-file)
  (->> (pubmed-api/get-pmc-file-nxml (babashka.fs/file temp-file))
       pubmed-api/nxml-text)

  ;; Create fine-tuning job
  (openai-helpers/create-fine-tuning-job (io/file "components/ctdbase-interactions/data/chem-gene-ixn-train.jsonl"))

  ;; Evaluate fine-tuned model on test set
  (do
    (def results
      (future
        (openai-helpers/get-model-test-result-seq
         "ft:gpt-3.5-turbo-0613:https-insilica-co::81I7Vkeg"
         (io/file "components/ctdbase-interactions/data/chem-gene-ixn-test.jsonl")
         :max-prompt-tokens 3074
         :append-at-end "\n\nRespond with a JSON array of objects in this format: [{\"ChemicalName\":\"Estradiol\",\"GeneSymbol\":\"IGF1\",\"GeneForms\":\"protein\",\"Organism\":\"Bos taurus\",\"Interaction\":\"Valproic Acid inhibits the reaction [IGF1 protein results in increased abundance of Estradiol]\",\"InteractionActions\":\"decreases^reaction|increases^abundance\"},{\"ChemicalName\":\"Estradiol\",\"GeneSymbol\":\"IGF1\",\"GeneForms\":\"protein\",\"Organism\":\"Bos taurus\",\"Interaction\":\"IGF1 protein results in increased abundance of Estradiol\",\"InteractionActions\":\"increases^abundance\"}]")))
    (future
      (doseq [r @results]
        (prn r))
      (prn "complete:" (count @results))))

  ;; Save test results
  (with-open [w (io/writer "components/ctdbase-interactions/data/chem-gene-ixn-test-completions.jsonl")]
    (doseq [r @results]
      (json/write r w)
      (.write w "\n")))

  ;; Load test results
  (def results
    (future (with-open [rdr (io/reader "components/ctdbase-interactions/data/chem-gene-ixn-test-completions.jsonl")]
              (->> rdr line-seq (map #(json/read-str % :key-fn keyword)) doall))))

  ;; Compare test interactions with completions
  (doseq [r (take 100 @results)]
    (compare-test-interactions r))
  )
