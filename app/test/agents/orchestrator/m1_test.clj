(ns agents.orchestrator.m1-test
  (:require [clojure.test :refer :all]
            [agents.orchestrator.core :as core]
            [agents.orchestrator.agentd :as agentd]
            [agents.orchestrator.managerd :as managerd]
            [clojure.java.io :as io]))

(defn- random-job-id []
  (str "job-" (System/currentTimeMillis) "-" (rand-int 1000)))

(deftest m1-test
  (let [job-id (random-job-id)
        job-dir (core/get-path "jobs" job-id)
        prompt {:role "SeniorEngineer"
                :rubric "Implement the change and produce a PR-ready diff."
                :allowed_paths ["src/" "docs/" "tests/"]
                :success "Tests pass; reviewer rubric satisfied."
                :routing {:mode "manager"}}]
    (try
      (io/make-parents (io/file job-dir "prompt.json"))
      (core/write-json (io/file job-dir "prompt.json") prompt)
      (let [agent-thread (Thread. #(agentd/-main {:role "SeniorEngineer" :workers 1}))
            manager-thread (Thread. #(managerd/-main {:auto-plan "off"}))]
        (.start agent-thread)
        (.start manager-thread)
        (let [completed-dir (core/get-path "agents" "Manager" "completed" job-id)]
          (loop [retries 10]
            (when (and (not (.exists completed-dir)) (> retries 0))
              (Thread/sleep 1000)
              (recur (dec retries))))
          (is (.exists completed-dir))
          (let [next-incoming-dir (core/get-path "agents" "CodeReviewer" "incoming" job-id)]
            (is (not (.exists next-incoming-dir))))
          (let [prompt-result (core/read-json (io/file completed-dir "prompt.json"))]
            (is (= prompt prompt-result)))))
      (finally
        (agentd/stop-workers)
        (managerd/stop-manager)
        (io/delete-file job-dir true)))))
