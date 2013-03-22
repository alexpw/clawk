(ns clawk.main
  (:require ;[clojure.core.reducers :as r]
            [clojure.string :as string]
            [clojure.repl :refer [pst]]
            [clojure.edn :as edn]
            [clojure.zip :as zip]
            [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [cli]]
            [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clj-http.client :as http]
            [clawk.macros :as m])
  (:gen-class))

(defn split-by
  [^String v]
  (let [[_ pattern] (re-matches #"^#\"(.*)\"$" v)] ; Could probably do this with read-string
    (if pattern
      ; split by regex
      (let [cp (java.util.regex.Pattern/compile pattern)]
        (fn [line]
          (->> (string/split line cp)
               (mapv string/trim))))

      ; split by exact string
      (fn [line]
        (->> (java.util.StringTokenizer. line v) ; ugh
             enumeration-seq
             (mapv string/trim))))))

(defn format-arg->reader
  [^String s]
  (get #{"edn" "json" "csv" "tsv" "text"} s "text"))

(defn mk-decoder
  [{:keys [format-in delimiter] :as opts}]
  (case format-in
    "edn"  edn/read-string
    "json" #(json/decode % true)
    "csv"  #(first (csv/read-csv % :separator \,))
    "tsv"  #(first (csv/read-csv % :separator \tab))
    "text" (if delimiter
             (split-by delimiter)
             identity)))

(defn mk-encoder
  [{:keys [format-out] :as opts}]
  (case format-out
    "edn"  identity
    "json" json/encode
    "csv"  #(csv/write-csv % :separator \,)
    "tsv"  #(csv/write-csv % :separator \tab)
    "text" identity))

(defn seqable-result
  "Ensures the result of the reduce step is iterable."
  [f result]
  (if (= f identity)
      result
      (if (sequential? result)
          result
          [result])))

(defn parse-args
  [args]
  (cli args
       ["-h" "--help" "Print this message" :flag true]
       ["-c" "--concat" "Apply concat to the result of the mapper (mapcat)."
        :default false
        :flag true]
       ["-g" "--debug" "Debug by printing stacktraces from exceptions."
        :default false
        :flag true]
       ["-d" "--delimiter" "Delimiter used to split each line (text only). A string or #\"regex\""
        :default nil]
       ["-i" "--format-in" "The input data format (edn, csv, tsv, json, text)"
        :default "edn"
        :parse-fn format-arg->reader]
       ["-o" "--format-out" "The output data format (edn, csv, tsv, json, text)"
        :default "edn"
        :parse-fn format-arg->reader]
       ["-n" "--new-lines" "Whether to emit new-lines after each line of output."
        :default true
        :flag true]
       ["-p" "--parallel" "Parallelized processing (pmap instead of map)."
        :default false
        :flag true]
       ["-t" "--trim" "Trim each line before decoding."
        :default true
        :flag true]
       ["-k" "--keep-blank" "Keep blank lines"
        :default false
        :flag true]
       ["-f" "--filter" "Filter fn (eval'd). It is supplied a var 'x'; example: '(not (empty? x))'"]
       ["-m" "--mapper" "Mapper fn (eval'd). It is supplied a var 'x'; example: '(inc x)'"]
       ["-r" "--reducer" "Reducer fn (eval'd). It is supplied vars 'xs' and 'x'; example: '(+ xs x)'"]
       ["-e" "--map-exception" "Exception handler for the map fn. Given args [e x]"]
       ["-u" "--reduce-exception" "Exception handler for the reduce fn. Given args [e xs x]"]
       ["-y" "--identity" "Used as the initializer value for the -r opt (only); example for a seq of numbers: 0."
        :default []
        :parse-fn edn/read-string]
       ))

(defn main
  [& args]
  (let [[opts code-files help-string] (parse-args args)]
    (if (:help opts)
      (println help-string)

      ; This *ns* thing seems like witch-craft to me so it's probably wrong/terrible
      (binding [*ns* (create-ns 'user)]
        (refer-clojure)
        ; This allows shorter access to helpful libs, subject to change
        (require '[cheshire.core :as json]
                 '[clojure.edn :as edn]
                 '[clj-http.client :as http]
                 '[clojure.string :as string]
                 '[clojure.xml :as xml]
                 '[clojure.zip :as zip])

        (try

          (when (:debug opts)
            (prn "OPTS" opts code-files))

          (when-not (empty? code-files)
            (doseq [f code-files]
              (when-let [code (if (.isFile (io/file f)) (slurp f) f)]
                (eval (read-string (str "(do " code ")"))))))

          (let [decoder   (mk-decoder opts)
                encoder   (mk-encoder opts)

                reduce-ex (if (:reduce-exception opts)
                              (eval `(fn [~'e ~'xs ~'x]
                                        ~(read-string (:reduce-exception opts))))
                              (fn [e xs x]
                                (when (:debug opts)
                                  (pst e 100))
                                xs))

                reducer   (if (:reducer opts)
                              (partial reduce
                                       (eval `(fn [~'xs ~'x]
                                                ~(try
                                                   (read-string (:reducer opts))
                                                   (catch Exception e
                                                     (reduce-ex e ~'xs ~'x)))))
                                       (:identity opts))
                              (partial remove nil?))

                printer   (if (= (:format-out opts) "edn")
                              (if (:new-lines opts) prn pr)
                              (if (:new-lines opts) println print))

                map-ex    (if (:map-exception opts)
                              (eval `(fn [~'e ~'x]
                                        ~(read-string (:map-exception opts))))
                              (fn [e x]
                                (when (:debug opts)
                                  (pst e 100))))

                map-fn    (if (:parallel opts) pmap map)

                mapper    (if (:mapper opts)
                              (eval `(fn [~'x]
                                      ~(try
                                         (read-string (:mapper opts))
                                         (catch Exception e
                                           (map-ex e ~'x)))))
                              identity)

                filterer  (if (:filter opts)
                              (eval `(fn [~'x]
                                       (when ~(read-string (:filter opts))
                                         ~'x)))
                              identity)

                concatter (if (:concat opts) (partial apply concat) identity)

                buffer    (line-seq (io/reader *in*))
                trimmer   (if (= (:format-out opts) "text")
                              (if (:trim opts)
                                  string/trim
                                  identity)
                              identity)

                blanker   (if (:keep-blank opts) identity #(when-not (= "" %) %))
                cleaner   (if (:keep-blank opts) identity (partial remove nil?))

                ;; some-real-fn provides short-circuiting and will skip over identity placeholders
                handler   #(m/some-real-fn-> % trimmer blanker decoder filterer mapper)
                ]

            (doseq [x (m/some-real-fn->> buffer (map-fn handler)
                                              cleaner
                                              concatter
                                              reducer
                                              (seqable-result reducer))]
              (m/some-real-fn-> x encoder printer)))

            (flush)

          (when (:parallel opts) (shutdown-agents))

          (catch Exception e
            (when (:debug opts) (pst e 100)))

          )))))

(defn -main
  [& args]
  (apply main args)
  (System/exit 0))
