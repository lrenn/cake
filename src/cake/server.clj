(ns cake.server
  (:use [cake.contrib.find-namespaces :only [read-file-ns-decl]])
  (:require [cake.contrib.server-socket :as server-socket]
            [clojure.stacktrace :as stacktrace]
            complete)
  (:import [java.io File PrintStream InputStreamReader OutputStreamWriter PrintWriter OutputStream FileOutputStream ByteArrayInputStream ByteArrayInputStream StringReader]
           [clojure.lang LineNumberingPushbackReader]
           [java.net InetAddress]))

(def *ins*  nil)
(def *outs* nil)

(defonce num-connections (atom 0))

(defn print-stacktrace [e]
  (stacktrace/print-stack-trace e)
  (.flush *out*))

(defn validate-form []
  (println
   (try (while (not= ::EOF (read *in* false ::EOF)))
        "valid"
        (catch clojure.lang.LispReader$ReaderException e
          (if (.contains (.getMessage e) "EOF")
            "incomplete"
            "invalid")))))

(defn completions []
  (let [[prefix ns] (read)]
    (doseq [completion (complete/completions prefix ns)]
      (println completion))))

(defn reload-files []
  (let [files (read)]
    (doseq [file files]
      (if (not (.endsWith file ".clj"))
        (println "cannot reload non-clojure file:" file)
        (if-let [ns (second (read-file-ns-decl (java.io.File. file)))]
          (when (find-ns ns) ;; don't reload namespaces that aren't already loaded
            (try (load-file file)
                 (catch Exception e
                   (print-stacktrace e))))
          (println "cannot reload file without namespace declaration:" file))))))

(defn exit []
  (System/exit 0))

(defn quit []
  (if (= 0 @num-connections)
    (exit)
    (println "warning: refusing to quit because there are active connections")))

(defn- reset-in []
  (while (.ready *in*) (.read *in*)))

(defn repl []
  (let [marker (read)]
    (swap! num-connections inc)
    (clojure.main/repl
     :init   #(in-ns 'user)
     :prompt #(do (reset-in) (println (str marker (ns-name *ns*)))))
    (swap! num-connections dec)))

(defn eval-verbose [form]
  (try (eval form)
       (catch Exception e
         (println "evaluating form:" (prn-str form))
         (throw e))))

(defn eval-multi
  ([] (eval-multi (read)))
  ([form]
     (clojure.main/with-bindings
       (in-ns 'user)
       (if (vector? form)
         (doseq [f form] (eval-verbose f))
         (eval-verbose form)))))

(def default-commands
  {:validate    validate-form
   :completions completions
   :reload      reload-files
   :force-quit  exit
   :quit        quit
   :repl        repl
   :eval        eval-multi
   :ping        #(println "pong")})

(defn fatal? [e]
  (and (instance? clojure.lang.Compiler$CompilerException e)
       (instance? UnsatisfiedLinkError (.getCause e))))

(defn create [port f & commands]
  (let [commands (apply hash-map commands)]
    (server-socket/create-server port
      (fn [ins outs]
        (binding [*in*   (LineNumberingPushbackReader. (InputStreamReader. ins))
                  *out*  (OutputStreamWriter. outs)
                  *err*  (PrintWriter. #^OutputStream outs true)
                  *ins*  ins
                  *outs* outs]          
          (try
            (let [form (read)]
              (if (keyword? form)
                (when-let [command (or (commands form) (default-commands form))]
                  (command))
                (f form)))
            (catch Exception e
              (print-stacktrace e)
              (when (fatal? e) (System/exit 1))))))
      0 (InetAddress/getByName "localhost"))))

(defn redirect-to-log [logfile]
  (let [null-stream (ByteArrayInputStream. (byte-array []))
        null-writer (LineNumberingPushbackReader. (StringReader. ""))
        log-stream  (PrintStream. (FileOutputStream. logfile) true)
        log-writer  (PrintWriter. log-stream true)]
    (System/setIn  null-stream)
    (System/setErr log-stream)
    (System/setOut log-stream)
    (alter-var-root #'*in*  (fn [_] null-writer))
    (alter-var-root #'*out* (fn [_] log-writer))
    (alter-var-root #'*err* (fn [_] log-writer))))
