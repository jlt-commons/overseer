(ns overseer.library-test
  (:require [clojure.test :refer [deftest is]]
            [overseer.library :as lib]))

(deftest json->agent-test
  (let [a (lib/json->agent {"id" "w" "prompt" "p $SCREEN" "loop_interval_seconds" 5
                            "sensors" ["screen"]
                            "decision" {"type" "choice" "act" ["alert"]
                                        "criteria" {"alert" "Alert" "ok" "Fine"}}
                            "only_on_significant_change" true
                            "code" "(fn [ctx] nil)"})]
    (is (= :w (:id a)))
    (is (= 5000 (:interval-ms a)))
    (is (= [:screen] (:sensors a)))
    (is (= :choice (get-in a [:decision :type])))
    (is (= ["alert"] (get-in a [:decision :act])))
    (is (= {"alert" "Alert" "ok" "Fine"} (get-in a [:decision :criteria])))
    (is (true? (:only-on-change a))))
  (is (= 30000 (:interval-ms (lib/json->agent {"id" "w" "prompt" "p"}))))
  (is (thrown? Exception (lib/json->agent {"prompt" "p"})))
  (is (thrown? Exception (lib/json->agent {"id" "w"})))
  (is (thrown? Exception (lib/json->agent {"id" "w" "prompt" "p" "code" 1}))))

(deftest json->sensor-test
  (is (= {:id :disk :kind :text :cmd ["df"]} (lib/json->sensor {"id" "disk" "cmd" ["df"]})))
  (is (thrown? Exception (lib/json->sensor {"id" "Disk" "cmd" ["df"]})))
  (is (thrown? Exception (lib/json->sensor {"id" "disk"})))
  (is (thrown? Exception (lib/json->sensor {"id" "disk" "cmd" ["df"] "kind" "video"}))))
