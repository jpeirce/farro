(ns agents.orchestrator.core
  (:require [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.util.regex Pattern]))

(defn get-repo-root
  "Returns the absolute path to the repository root."
  []
  (.getCanonicalPath (io/file ".")))

(defn get-path
  "Returns a File object relative to the repository root."
  [& parts]
  (io/file (get-repo-root) (apply str (interpose "/" parts))))

(defn read-json
  "Reads and parses a JSON file."
  [path]
  (json/parse-string (slurp path) true))

(defn write-json
  "Writes data to a JSON file."
  [path data]
  (spit path (json/generate-string data {:pretty true})))

(defn expand-env-var
  "Expands a ${VAR} style environment variable in a string."
  [s]
  (let [matcher (re-matcher #"\$\{(.+?)\}" s)]
    (loop [result (StringBuffer.)]
      (if (.find matcher)
        (let [var-name (.group matcher 1)
              var-value (System/getenv var-name)]
          (if (nil? var-value)
            (throw (ex-info (str "Environment variable not set: " var-name) {:variable var-name}))
            (do
              (.appendReplacement matcher result (Pattern/quote var-value))
              (recur result))))
        (do
          (.appendTail matcher result)
          (.toString result))))))
