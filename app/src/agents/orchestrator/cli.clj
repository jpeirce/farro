(ns agents.orchestrator.cli
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [agents.orchestrator.core :as core]
            [agents.orchestrator.agentd :as agentd]
            [agents.orchestrator.managerd :as managerd]
            [clojure.java.io :as io])
  (:gen-class))

(def cli-options
  [["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Farro Orchestrator"
        ""
        "Usage: java -jar farro.jar <subcommand> [options]"
        ""
        "Subcommands:"
        "  init       Initialize the directory structure."
        "  doctor     Check configuration and environment."
        "  enqueue    Enqueue a new job."
        "  agentd     Run an agent daemon."
        "  managerd   Run the manager daemon."
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn init-directories []
  (let [dirs ["agents/Manager/incoming"
              "agents/Manager/in-progress"
              "agents/Manager/completed"
              "agents/SeniorEngineer/incoming"
              "agents/SeniorEngineer/in-progress"
              "agents/SeniorEngineer/completed"
              "agents/JuniorEngineer/incoming"
              "agents/JuniorEngineer/in-progress"
              "agents/JuniorEngineer/completed"
              "agents/Architect/incoming"
              "agents/Architect/in-progress"
              "agents/Architect/completed"
              "agents/CodeReviewer/incoming"
              "agents/CodeReviewer/in-progress"
              "agents/CodeReviewer/completed"
              "agents/DocWriter/incoming"
              "agents/DocWriter/in-progress"
              "agents/DocWriter/completed"
              "jobs"
              "logs"
              "tasks"
              "schemas"]]
    (doseq [dir dirs]
      (.mkdirs (io/file dir)))))

(defn doctor-check []
  (println "Doctor command not yet implemented."))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        command (first arguments)
        cmd-args (rest arguments)]
    (cond
      (:help options)
      (println (usage summary))

      errors
      (do (println (str/join \newline errors))
          (System/exit 1))

      :else
      (case command
        "init"     (do (init-directories)
                       (println "Initialized directory structure."))
        "doctor"   (doctor-check)
        "enqueue"  (println "Enqueue command not yet implemented.")
        "agentd"   (apply agentd/-main cmd-args)
        "managerd" (apply managerd/-main cmd-args)
        (println (usage summary))))))
