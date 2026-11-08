(ns overseer.decision-test
  (:require [clojure.test :refer [deftest is]]
            [overseer.decision :as d]))

(deftest question-constructor-test
  (is (= {"type" "noul" "instructions" "q"} (d/noul "q")))
  (is (= {"type" "choice" "instructions" "q" "criteria" {"a" "one" "b" "two"}}
         (d/choice "q" (array-map "a" "one" "b" "two"))))
  (is (= {"type" "choice" "instructions" "q" "criteria" {"a" nil "b" nil}}
         (d/choice "q" ["a" "b"])))
  (is (= {"type" "score" "instructions" "q" "criteria" ["low" "high"]}
         (d/score "q" ["low" "high"]))))

(deftest default-question-test
  (is (= "noul" (get (d/default-question {:type :noul :instructions "x"}) "type")))
  (is (= "choice" (get (d/default-question {:type :choice :criteria {"a" "b"}}) "type")))
  (is (= "score" (get (d/default-question {:type :score :criteria ["a" "b"]}) "type")))
  (is (thrown? Exception (d/default-question {:type :bogus}))))

(deftest answer-extraction-test
  (is (= 0.94 (d/noul-of {"noul" 0.94})))
  (is (= "dog" (d/choice-of {"choice" "dog"})))
  (is (= 2.0 (d/score-of {"score" 2.0})))
  (is (= 0.9 (d/confidence-of {"confidence" 0.9}))))
