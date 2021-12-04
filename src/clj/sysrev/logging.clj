;;;
;;; Logging setup, customized output
;;;

(ns sysrev.logging
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [org.slf4j.bridge SLF4JBridgeHandler]))

(defn logger-ns-to-string
  "Print namespace to string for inclusion in logging output."
  [ns]
  (-> (str ns)
      (str/replace #"^sysrev\.test\.browser\." "s.t.b.")
      (str/replace #"^sysrev\.test\.etaoin\." "s.t.et.")
      (str/replace #"^sysrev\.test\." "s.t.")
      (str/replace #"^sysrev\." "s.")))

;; Modified from clojure.tools.logging.impl/slf4j-factory
;; Uses logger-ns-to-string instead of str for printing ns
(defn slf4j-factory-custom
  "Returns a SLF4J-based implementation of the LoggerFactory protocol, or nil if
  not available."
  []
  (eval
   `(do
      (extend org.slf4j.Logger
        clojure.tools.logging.impl/Logger
        {:enabled?
         (fn [^org.slf4j.Logger logger# level#]
           (condp = level#
             :trace (.isTraceEnabled logger#)
             :debug (.isDebugEnabled logger#)
             :info  (.isInfoEnabled  logger#)
             :warn  (.isWarnEnabled  logger#)
             :error (.isErrorEnabled logger#)
             :fatal (.isErrorEnabled logger#)
             (throw (IllegalArgumentException. (str level#)))))
         :write!
         (fn [^org.slf4j.Logger logger# level# ^Throwable e# msg#]
           (let [^String msg# (str msg#)]
             (if e#
               (condp = level#
                 :trace (.trace logger# msg# e#)
                 :debug (.debug logger# msg# e#)
                 :info  (.info  logger# msg# e#)
                 :warn  (.warn  logger# msg# e#)
                 :error (.error logger# msg# e#)
                 :fatal (.error logger# msg# e#)
                 (throw (IllegalArgumentException. (str level#))))
               (condp = level#
                 :trace (.trace logger# msg#)
                 :debug (.debug logger# msg#)
                 :info  (.info  logger# msg#)
                 :warn  (.warn  logger# msg#)
                 :error (.error logger# msg#)
                 :fatal (.error logger# msg#)
                 (throw (IllegalArgumentException. (str level#)))))))})
      (reify clojure.tools.logging.impl/LoggerFactory
        (name [_#]
          "org.slf4j")
        (get-logger [_# logger-ns#]
          (org.slf4j.LoggerFactory/getLogger ^String (logger-ns-to-string logger-ns#)))))))

(defn init-logging []
  (SLF4JBridgeHandler/removeHandlersForRootLogger)
  (SLF4JBridgeHandler/install)
  ;; wrap log* implementation
  ;; (based on https://github.com/AvisoNovate/pretty/blob/master/src/io/aviso/logging.clj)
  #_ (alter-var-root #'log/log*
                     (fn [default-impl]
                       (fn [logger level throwable message]
                         (default-impl logger level throwable message))))
  (alter-var-root #'clojure.tools.logging/*logger-factory*
                  (constantly (slf4j-factory-custom)))
  (log/info "logging initialized")
  true)

(defonce ^:init-once logging-initialized
  (init-logging))
