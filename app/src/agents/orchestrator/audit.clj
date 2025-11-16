(ns agents.orchestrator.audit
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.io RandomAccessFile]
           [java.util.concurrent Executors TimeUnit]))

(defonce audit-log-path "logs/audit.log")
(defonce audit-channel (async/chan 100))
(defonce scheduled-executor (Executors/newSingleThreadScheduledExecutor))

(defn- write-audit-entry [entry]
  (let [log-file (io/file audit-log-path)]
    (with-open [writer (RandomAccessFile. log-file "rw")]
      (.seek writer (.length writer))
      (.writeBytes writer (str (json/generate-string entry) \newline))
      (-> writer .getFD .sync))))

(defn- audit-loop []
  (async/go-loop []
    (when-let [entry (async/<! audit-channel)]
      (try
        (write-audit-entry entry)
        (catch Exception e
          (log/error e "Failed to write audit entry")))
      (recur))))

(defn start-audit-writer [mode]
  (let [task (fn [] (async/go (write-audit-entry {:timestamp (System/currentTimeMillis) :event "flush"})))]
    (case mode
      :strict (audit-loop)
      :buffered (do (.scheduleAtFixedRate scheduled-executor task 1 1 TimeUnit/SECONDS)
                     (audit-loop)))))

(defn stop-audit-writer []
  (.shutdown scheduled-executor))

(defn log-event [event]
  (async/>!! audit-channel event))

