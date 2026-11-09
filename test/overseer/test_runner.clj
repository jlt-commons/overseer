(ns overseer.test-runner
  (:require [clojure.test :as t]
            overseer.agent-test
            overseer.config-test
            overseer.decision-test
            overseer.library-test
            overseer.memory-test
            overseer.notify-test
            overseer.sensor-test
            overseer.server-test
            overseer.tool-test))

(defn -main [& _]
  (let [{:keys [fail error]}
        (t/run-tests 'overseer.agent-test
                     'overseer.config-test
                     'overseer.decision-test
                     'overseer.library-test
                     'overseer.memory-test
                     'overseer.notify-test
                     'overseer.sensor-test
                     'overseer.server-test
                     'overseer.tool-test)]
    (flush)
    (System/exit (if (zero? (+ fail error)) 0 1))))
