(ns sysrev.util-lite.core
  (:require [clojure.tools.logging :as log])
  (:import java.security.MessageDigest
           java.util.Base64))

(defn full-name ^String [x]
  (cond
    (nil? x) nil
    (string? x) x
    (simple-ident? x) (name x)
    (ident? x) (str (namespace x) "/" (name x))))

(def retry-recur-val ::retry-recur)

(defmacro retry [opts & body]
  `(let [opts# ~opts
         throw-pred# (or (:throw-pred opts#) (constantly false))]
     (loop [interval-ms# (:interval-ms opts#)
            n# (:n opts#)]
     ;; Can't recur from inside the catch, so we use a special return
     ;; value to signal the need to recur.
       (let [ret#
             (try
               ~@body
               (catch Exception e#
                 (if (and (pos? n#) (not (throw-pred# e#)))
                   (do
                     (log/info e# "Retrying after" interval-ms# "ms due to Exception")
                     retry-recur-val)
                   (throw e#))))]
         (if (= ret# retry-recur-val)
           (do
             (Thread/sleep interval-ms#)
             (recur (+ interval-ms# interval-ms# (.longValue ^Integer (rand-int 100))) (dec n#)))
           ret#)))))

(defn wait-timeout [pred & {:keys [timeout-f timeout-ms]}]
  {:pre [(fn? pred) (fn? timeout-f) (number? timeout-ms)]}
  (let [start (System/nanoTime)
        timeout-ns (* timeout-ms 1000000)]
    (loop []
      (let [result (pred)]
        (cond
          result result
          (< timeout-ns (- (System/nanoTime) start)) (timeout-f)
          :else (recur))))))

;; https://lambdaisland.com/blog/12-06-2017-clojure-gotchas-surrogate-pairs
;; Adapted to handle unpaired surrogates
(defn char-seq
  "Return a seq of the characters in a string, making sure not to split up
  UCS-2 (or is it UTF-16?) surrogate pairs. Because JavaScript. And Java."
  ([str]
   (char-seq str 0))
  ([str offset]
   (if (>= offset (count str))
     ()
     (lazy-seq
      (let [code (.charAt str offset)
            next-code (if (< (+ offset 1) (count str)) (.charAt str (+ offset 1)) 0)
            width (if (and (<= 0xD800 (int code) 0xDBFF)
                           (<= 0xDC00 (int next-code) 0xDFFF)) 2 1)] ; detect valid surrogate pair
        (cons (subs str offset (+ offset width))
              (char-seq str (+ offset width))))))))

(defn sanitize-str [^String s]
  (->> s char-seq
       (remove
        (fn [s]
          (when (= 1 (count s))
            (let [c (first s)]
              (or (Character/isLowSurrogate c)
                  (Character/isHighSurrogate c))))))
       (apply str)))

(defn sha256-base64 [s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes s "UTF-8"))]
    (.encodeToString (Base64/getEncoder) bytes)))
