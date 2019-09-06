(ns sysrev.util
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.main :refer [demunge]]
            [clojure.math.numeric-tower :as math]
            [clojure.tools.logging :as log]
            [clojure.data.xml :as dxml]
            [crypto.random]
            [cognitect.transit :as transit]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [me.raynes.fs :as fs]
            [sysrev.config.core :refer [env]]
            [sysrev.shared.util :as sutil :refer [ensure-pred]])
  (:import java.util.UUID
           (java.io File ByteArrayInputStream ByteArrayOutputStream)
           (java.util.zip GZIPInputStream)
           java.math.BigInteger
           java.security.MessageDigest
           org.joda.time.DateTime
           org.apache.commons.lang3.exception.ExceptionUtils))

(defn should-never-happen-exception []
  (ex-info "this should never happen" {:type :should-never-happen}))

(defn xml-find [roots path]
  (try
    (let [roots (if (map? roots) [roots] roots)]
      (if (empty? path)
        roots
        (xml-find
         (flatten
          (map (fn [root]
                 (filter (fn [child]
                           (= (:tag child) (first path)))
                         (:content root)))
               roots))
         (rest path))))
    (catch Throwable e
      nil)))

(defn xml-find-value [roots path]
  (-> (xml-find roots path) first :content first))

(defn xml-find-vector [roots path]
  (->> (xml-find roots path)
       (mapv #(-> % :content first))))

(defn parse-xml-str [s] (dxml/parse-str s))

(defn all-project-ns []
  (->> (all-ns)
       (map ns-name)
       (map name)
       (filter #(re-find #"sysrev" %))
       (map symbol)
       (map find-ns)))

(defn ^:repl clear-project-symbols [syms]
  (let [syms (if (coll? syms) syms [syms])]
    (doseq [ns (all-project-ns)]
      (doseq [sym syms]
        (ns-unmap ns sym)))))

(defn ^:repl clear-project-aliases [alias]
  (doseq [ns (all-project-ns)]
    (ns-unalias ns alias)))

(defn ^:repl reload
  "Reload sysrev.user namespace to update referred symbols."
  []
  (require 'sysrev.user :reload))

(defn ^:repl reload-all
  "Reload code for all project namespaces."
  []
  (let [ ;; Reload project namespaces
        failed (->> (all-project-ns)
                    (mapv #(try
                             (do (require (ns-name %) :reload) nil)
                             (catch Throwable e
                               (log/warn (ns-name %) "failed:" (.getMessage e))
                               %)))
                    (remove nil?))]
    ;; Try again for any that failed
    ;; (may have depended on changes from another namespace)
    (doseq [ns failed]
      (require (ns-name ns) :reload))
    ;; Update sysrev.user symbols
    (reload)))

(defn map-to-arglist
  "Converts a map to a vector of function keyword arguments."
  [m]
  (->> m (mapv identity) (apply concat) vec))

(defn crypto-rand []
  (let [size 4
        n-max (math/expt 256 size)
        n-rand (BigInteger. 1 (crypto.random/bytes size))]
    (double (/ n-rand n-max))))

(defn crypto-rand-int [n]
  (let [size 4
        n-rand (BigInteger. 1 (crypto.random/bytes size))]
    (int (mod n-rand n))))

(defn crypto-rand-nth [coll]
  (nth coll (crypto-rand-int (count coll))))

;; see: https://groups.google.com/forum/#!topic/clojure/ORRhWgYd2Dk
;;      https://stackoverflow.com/questions/22116257/how-to-get-functions-name-as-string-in-clojure
(defmacro current-function-name
  "Returns a string, the name of the current Clojure function."
  []
  `(-> (Throwable.) .getStackTrace first .getClassName demunge))

(defn today-string
  "Returns string of current date, by default in the form
  YYYYMMDD. Optional time-format value (default :basic-date) may be
  given as either a keyword (from clj-time.format/formatters) or a
  string (custom format via clj-time.format/formatter)."
  ([]
   (today-string :basic-date))
  ([time-format]
   (-> (cond (keyword? time-format) (tf/formatters time-format)
             (string? time-format)  (tf/formatter time-format)
             :else                  (tf/formatters :basic-date))
       (tf/unparse (t/now)))))

(defn parse-time-string
  "Returns DateTime (clj-time) object from :mysql format time
  string. Compatible with `write-time-string`."
  [s & [formatter]]
  (tf/parse (tf/formatter :mysql) s))

(defn to-clj-time
  "Converts various types to clj-time compatible DateTime. Integer
  values are treated as Unix epoch seconds. Tries to parse strings
  as :mysql format using `parse-time-string`."
  [t]
  (or (cond (= (type t) DateTime)             t
            (= (type t) java.sql.Timestamp)   (tc/from-sql-time t)
            (= (type t) java.sql.Date)        (tc/from-sql-date t)
            (integer? t)                      (tc/from-epoch t)
            (string? t)                       (parse-time-string t))
      (throw (ex-info "to-clj-time: unable to convert value" {:value t}))))

(defn to-epoch
  "Converts various types to clj-time compatible Unix epoch seconds. See
  `to-clj-time`."
  [t]
  (-> t (to-clj-time) (tc/to-epoch)))

(defn write-time-string
  "Returns :mysql format time string from time value. Compatible with
  `parse-time-string`."
  [t & [formatter]]
  (tf/unparse (tf/formatters (or formatter :mysql))
              (to-clj-time t)))

(defn now-ms []
  (-> (t/now) (tc/to-long)))

(defmacro with-print-time-elapsed
  "Runs `body` and logs elapsed time on completion."
  [name & body]
  `(let [start# (now-ms)]
     (try ~@body (finally (log/infof "%s finished in %.2fs"
                                     ~name (/ (- (now-ms) start#) 1000.0))))))

;; see: https://stackoverflow.com/questions/10751638/clojure-rounding-to-decimal-places
(defn round
  "Round a double to the given precision (number of significant digits)"
  [precision d]
  (let [factor (Math/pow 10 precision)]
    (/ (Math/round (* d factor)) factor)))

(defn round-to
  "Round a double to the closest multiple of interval, then round to
  precision (number of significant digits).

  op controls which operation to use for the first round. Allowed
  values are [:round :floor :ceil]."
  [d interval precision & {:keys [op] :or {op :round}}]
  (let [x (/ d interval)]
    (->> interval
         (* (case op
              :round (Math/round x)
              :floor (Math/floor x)
              :ceil (Math/ceil x)))
         (round precision))))

;; see: https://gist.github.com/jizhang/4325757
(defn byte-array->md5-hash
  "Convert a byte-array into an md5 hash"
  [^"[B" bytes]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm bytes)]
    (format "%032x" (BigInteger. 1 raw))))

(defn string->md5-hash
  "Convert a string into an md5 hash"
  [^String s]
  (byte-array->md5-hash (.getBytes s)))

(defn byte-array->sha-1-hash
  "Convert a byte-array into an md5 hash"
  [^"[B" bytes]
  (let [algorithm (MessageDigest/getInstance "SHA-1")
        raw (.digest algorithm bytes)]
    (format "%x" (BigInteger. 1 raw))))

(defn string->sha-1-hash
  "Convert a string into a sha-1 hash"
  [^String s]
  (byte-array->sha-1-hash (.getBytes s)))

(defn file->byte-array
  "Convert a file into a byte-array"
  [^java.io.File file]
  (let [ary (byte-array (.length file))]
    (with-open [istream (java.io.FileInputStream. file)]
      (.read istream ary))
    ary))

(defn file->sha-1-hash
  "Convert a file into a sha-1 hash"
  [^java.io.File file]
  (-> file
      file->byte-array
      byte-array->sha-1-hash))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing.
  Taken from http://stackoverflow.com/a/26372677"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

(defn wrap-retry
  [f & {:keys [fname max-retries retry-delay throttle-delay]
        :or {max-retries 3, retry-delay 1500, throttle-delay nil}}]
  (letfn [(result-fn [retry-count]
            (when throttle-delay
              (Thread/sleep throttle-delay))
            (try
              (f)
              (catch Throwable e
                (if (> (inc retry-count) max-retries)
                  (do (log/warn (format "wrap-retry [%s]: failed (%d retries)"
                                        (if fname (str fname) "unknown")
                                        max-retries))
                      (throw e))
                  (do (log/info (format "wrap-retry [%s]: retrying (%d / %d)"
                                        (if fname (str fname) "unknown")
                                        (inc retry-count)
                                        max-retries))
                      (Thread/sleep retry-delay)
                      (result-fn (inc retry-count)))))))]
    (result-fn 0)))

(defn shell
  "Runs a shell command, throwing exception on non-zero exit."
  [& args]
  (let [{:keys [exit out err] :as result}
        (apply sh args)]
    (if (zero? exit)
      result
      (do (log/error (str (pr-str args) ":") "Got exit code" exit)
          (log/error (str "stdout\n" out))
          (log/error (str "stderr\n" err))
          (throw (Exception. (pr-str result)))))))

(defn create-tempfile [& {:keys [suffix]}]
  (let [file (File/createTempFile "sysrev-" suffix)]
    (.deleteOnExit file)
    file))

(defn ex-summary
  "Returns string showing type and message from exception."
  [ex]
  (str (type ex) " - " (.getMessage ex)))

(defn ex-log-message
  "Returns a string summarizing exception for logging purposes."
  [ex]
  (format "%s\n%s" (str ex) (->> (ExceptionUtils/getStackTrace ex)
                                 (str/split-lines)
                                 (filter #(str/includes? % "sysrev"))
                                 (take 5)
                                 (str/join "\n"))))

;; from https://github.com/remvee/clj-base64/blob/master/src/remvee/base64.clj
(def base64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
;;
(defn bytes->base64
  "Encode sequence of bytes to a sequence of base64 encoded characters."
  [bytes]
  (letfn [(encode [bytes]
            (when (seq bytes)
              (let [t (->> bytes (take 3) (map #(bit-and (int %) 0xff)))
                    v (int (reduce (fn [a b] (+ (bit-shift-left (int a) 8) (int b))) t))
                    f #(nth base64-alphabet (bit-and (if (pos? %)
                                                       (bit-shift-right v %)
                                                       (bit-shift-left v (* % -1)))
                                                     0x3f))
                    r (condp = (count t)
                        1 (concat (map f [2 -4])    [\= \=])
                        2 (concat (map f [10 4 -2]) [\=])
                        3         (map f [18 12 6 0]))]
                (concat r (lazy-seq (encode (drop 3 bytes)))))))]
    (apply str (encode bytes))))

(defn ^:repl rename-flyway-files
  "Handles renaming flyway sql files for formatting. Returns a sequence
  of shell commands that can be used to rename all of the
  files. directory should be path containing the sql
  files. primary-width and extra-width control zero-padding on index
  numbers in file names. mv argument sets shell command for mv
  (e.g. \"git mv\")."
  [directory & {:keys [primary-width extra-width mv]
                :or {primary-width 4 extra-width 3 mv "mv"}}]
  (->> (fs/list-dir directory)
       (map #(str (fs/name %) (fs/extension %)))
       (map #(re-matches #"(V0\.)([0-9]+)(\.[0-9]+)*(__.*)" %))
       (map (fn [[original start n extra end]]
              [(str start
                    (format (str "%0" primary-width "d") (sutil/parse-integer n))
                    (when extra
                      (->> (str/split extra #"\.")
                           (map #(some->> (sutil/parse-integer %)
                                          (format (str "%0" extra-width "d"))))
                           (str/join ".")))
                    end)
               original]))
       (filter (fn [[changed original]] (not= changed original)))
       sort
       (map (fn [[changed original]] (str/join " " [mv original changed])))))

(defn ms-windows? []
  (-> (System/getProperty "os.name")
      (str/includes? "Windows")))

(defn temp-dir []
  (str (or (:tmpdir env) (:java-io-tmpdir env))
       (if (ms-windows?) "\\" "/")))

(defn tempfile-path [filename]
  (str (temp-dir) filename))

(defmacro with-tempfile
  "Runs `body` with `file` bound to a new temporary file and ensures the
  file is deleted when this form completes."
  [[file & {:keys [suffix]}] & body]
  `(let [~file (create-tempfile :suffix ~suffix)]
     (try ~@body
          (finally (io/delete-file ~file)))))

(defmacro with-gunzip-file
  "Decompresses gzipped file `input-file` and runs `body` with
  `tempfile` bound to the decompressed file, ensuring the temporary
  file is deleted when this form completes."
  [[tempfile input-file & {:keys [suffix]}] & body]
  `(with-open [gz-stream# (-> ~input-file io/file io/input-stream GZIPInputStream.)]
     (with-tempfile [~tempfile :suffix ~suffix]
       (io/copy gz-stream# ~tempfile)
       ~@body)))

(defn pp-str [x]
  (with-out-str (pp/pprint x)))

(defn random-uuid []
  (UUID/randomUUID))
