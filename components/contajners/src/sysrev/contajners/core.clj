(ns sysrev.contajners.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [contajners.core :as cj])
  (:import (clojure.lang ExceptionInfo)))

(defn client [category]
  (cj/client {:category category
              :conn {:uri "unix:///var/run/docker.sock"}
              :engine :docker
              :version "v1.41"}))

(defn invoke! [& {:keys [category throw-exceptions] :as op-map}]
  (let [op-map (if (contains? op-map :throw-exceptions)
                 op-map
                 (assoc op-map :throw-exceptions true))
        client (or (:client op-map) (client category))]
    (cj/invoke client op-map)))

(defn pull-image! [name & {:as op-map}]
  (log/info "Pulling" name)
  (invoke!
   (assoc
    op-map
    :category :images
    :op :ImageCreate
    :params {:fromImage name}))
  (log/info "Pulled" name))

(defn image-exists? [name & {:as op-map}]
  (try
    (invoke!
     (assoc
      op-map
      :category :images
      :op :ImageInspect
      :params {:name name}))
    true
    (catch ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        false
        (throw e)))))

(defn create-container! [name data & {:as op-map}]
  (log/info "Creating" name)
  (try
    (invoke!
     (assoc
      op-map
      :category :containers
      :op :ContainerCreate
      :data data
      :params {:name name}))
    nil
    (catch ExceptionInfo e
      ; 409 occurs when the container already exists
      (when (not= 409 (:status (ex-data e)))
        (throw e)))))

(defn start-container! [name & {:as op-map}]
  (log/info "Starting" name)
  (invoke!
   (assoc
    op-map
    :category :containers
    :op :ContainerStart
    :params {:id name})))

(defn stop-container! [name & {:as op-map}]
  (log/info "Stopping" name)
  (invoke!
   (assoc
    op-map
    :category :containers
    :op :ContainerStop
    :params {:id name})))

(defn up! [name {:keys [Image] :as config} & {:as op-map}]
  (pull-image! Image op-map)
  (while (not (image-exists? Image op-map))
    (Thread/sleep 1000))
  (create-container! name config op-map)
  (start-container! name op-map))

(defn inspect-container [name & {:as op-map}]
  (invoke!
   (assoc
    op-map
    :category :containers
    :op :ContainerInspect
    :params {:id name})))

(defn simplify-ports
  "Turns #:5432{:tcp [{:HostIp \"0.0.0.0\", :HostPort \"49154\"}
                      {:HostIp \"::\", :HostPort \"49154\"}]}
   into {5432 #{49154}}."
  [ports]
  (reduce-kv
   (fn [m k v]
     (let [container-port (-> k namespace parse-long)]
       (->> (map (comp parse-long :HostPort) v)
            ;; Combine ports from different protocols with the same port number
            (update m container-port (fnil into #{})))))
   {}
   ports))
 
(defn container-ports [name & {:as op-map}]
  (->> (inspect-container name op-map)
       :NetworkSettings :Ports
       simplify-ports))
