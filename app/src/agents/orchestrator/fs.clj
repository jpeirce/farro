(ns agents.orchestrator.fs
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import [java.nio.file Files FileSystems StandardCopyOption Path]
           [java.nio.channels OverlappingFileLockException FileChannel]
           [java.io File RandomAccessFile]))

(defn- get-file-store [^Path path]
  (Files/getFileStore path))

(defn check-same-volume
  "Checks if two paths reside on the same volume. Throws an exception if not."
  [^Path src ^Path dest]
  (let [src-store (get-file-store src)
        dest-store (get-file-store (.getParent dest))]
    (when-not (= src-store dest-store)
      (throw (ex-info "Cross-volume move detected"
                      {:source-path src
                       :source-volume (.name src-store)
                       :dest-path dest
                       :dest-volume (.name dest-store)})))
    true))

(defn atomic-move
  "Atomically moves a directory. Fails fast on cross-volume moves."
  [^File src ^File dest]
  (let [src-path (.toPath src)
        dest-path (.toPath dest)]
    (check-same-volume src-path dest-path)
    (Files/move src-path dest-path (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (log/infof "Atomically moved %s to %s" src dest)))

(defn create-lock
  "Creates a lock file for a job directory. Returns the channel and lock, or nil if already locked."
  [^File job-dir]
  (let [lock-file (io/file job-dir "lock")
        raf (RandomAccessFile. lock-file "rw")
        channel (.getChannel raf)]
    (try
      (let [lock (.tryLock channel)]
        (if lock
          {:channel channel :lock lock :raf raf}
          (do
            (.close channel)
            (.close raf)
            nil)))
      (catch OverlappingFileLockException _
        (.close channel)
        (.close raf)
        nil))))

(defn release-lock
  "Releases a file lock and deletes the lock file."
  [{:keys [channel lock raf]} ^File job-dir]
  (let [lock-file (io/file job-dir "lock")]
    (when lock (.release lock))
    (when channel (.close channel))
    (when raf (.close raf))
    (io/delete-file lock-file true)))
