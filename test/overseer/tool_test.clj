(ns overseer.tool-test
  (:require [clojure.test :refer [deftest is]]
            [overseer.tool :as tool]))

(deftest register-and-call-test
  (let [reg (atom {})]
    (tool/register! reg {:name "greet" :fn (fn [_ who] (str "hi " who))})
    (is (= "hi bob" (tool/call reg {} "greet" "bob")))
    (is (thrown? Exception (tool/register! reg {:name "bad"})))
    (is (thrown? Exception (tool/call reg {} "missing")))
    ;; unknown tool names the known set in its data
    (is (= :unknown-tool (:type (ex-data (try (tool/call reg {} "missing")
                                              (catch Exception e e))))))))

(deftest name-normalization-test
  (let [reg (atom {})]
    (tool/register! reg {:name "do-thing" :fn (fn [_] :done)})
    (is (= :done (tool/call reg {} "do-thing")))
    (is (= :done (tool/call reg {} :do-thing)))))
