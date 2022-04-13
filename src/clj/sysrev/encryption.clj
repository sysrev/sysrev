(ns sysrev.encryption
  (:require [buddy.core.codecs :as codecs]
            [buddy.core.nonce :as nonce]
            [buddy.sign.jwt :as jwt]))

;; Will be new on each server reboot
(def key32 (nonce/random-bytes 32))

(defn encrypt [data]
  (jwt/encrypt data key32))

(defn decrypt [data]
  (jwt/decrypt data key32))

(defn encrypt-wrap64 [data]
  (-> data encrypt .getBytes codecs/bytes->b64 codecs/bytes->str))

(defn decrypt-wrapped64 [data]
  (-> data codecs/str->bytes codecs/b64->bytes codecs/bytes->str decrypt))

(defn try-decrypt-wrapped64 [data]
  (try
    (decrypt-wrapped64 data)
    (catch Exception _
      nil)))
