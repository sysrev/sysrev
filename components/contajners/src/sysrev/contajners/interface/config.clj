(ns sysrev.contajners.interface.config
  (:require [clojure.string :as str]))

(defn add-port
  "Adds a port mapping to a container config."
  [config host-port target-port]
  {:pre [(map? config) (nat-int? host-port) (pos-int? target-port)]}
  (-> config
      (assoc-in [:ExposedPorts (str target-port "/tcp")] {})
      (assoc-in [:HostConfig :PortBindings (str target-port "/tcp")]
                [{:HostPort (str host-port)}])))

(defn add-tmpfs
  "Adds a tmpfs path to a container config. Tmpfs is only supported
   in Docker on Linux."
  [config path]
  {:pre [(map? config) (string? path) (not (str/blank? path))]}
  (-> config
      (update-in [:HostConfig :Mounts]
                 (fnil conj [])
                 {:Target path :Type "tmpfs"})
      (assoc-in [:HostConfig :Tmpfs path] "rw")
      (assoc-in [:Volumes path] {})))

(defn linux?
  "Returns whether the host OS name includes \"linux\"."
  []
  (-> (System/getProperty "os.name")
      str/lower-case
      (str/includes? "linux")))
