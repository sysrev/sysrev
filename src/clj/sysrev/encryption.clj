(ns sysrev.encryption
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.nonce :as nonce]))

;; Will be new on each server reboot
(def key32 (nonce/random-bytes 32))

(defn encrypt [data]
  (jwt/encrypt data key32))

(defn decrypt [data]
  (jwt/decrypt data key32))
