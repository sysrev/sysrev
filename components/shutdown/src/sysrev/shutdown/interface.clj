(ns sysrev.shutdown.interface
  (:require [sysrev.shutdown.core :as core]))

(defn add-hook!
  "Register a 0-arity function to be called on orderly JVM shutdown.
   Hooks should execute quickly. There are no guarantees about execution order or
   whether or not a hook will be executed at all.

   Returns a delay that should be derefed when the hook needs to be
   executed before JVM shutdown. Using the delay prevents memory leaks
   and ensures that the hook is only executed once.

   May throw if called during JVM shutdown.

   See also:   
   https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Runtime.html#addShutdownHook(java.lang.Thread)"
  [thunk]
  (core/add-hook! thunk))
