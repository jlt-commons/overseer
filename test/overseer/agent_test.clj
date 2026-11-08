(ns overseer.agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [overseer.agent :as agent]
            [overseer.memory :as mem]))

(defn- fake-engine
  "An engine answering \"act\" with the given answer map."
  [answer]
  (fn [_state questions]
    {"answers" (into {} (map (fn [[qid _]] [qid answer]) questions))}))

(defn- test-system [engine]
  (agent/attach-manager
   (agent/make-system
    {:engine engine
     :sensors {:screen {:id :screen :kind :text :capture (fn [_] "screen-text")}}
     :tools {}
     :store (mem/make-store)
     :config {}})))

(deftest run-once-test
  (let [result (atom nil)
        system (test-system (fake-engine {"type" "noul" "noul" 0.94 "confidence" 0.9}))
        a {:id :t :prompt "state $SCREEN / $MEMORY" :sensors [:screen]
           :decision {:type :noul :instructions "act?" :threshold 0.8}
           :code (fn [ctx] (reset! result ctx))}]
    (agent/run-once system a)
    (let [ctx @result]
      (is (= :t (:agent-id ctx)))
      (is (= 0.94 (get-in ctx [:decision "act" "noul"])))
      (is (true? (:act? ctx)))
      (is (= "screen-text" (:screen ctx)))
      (is (= "state screen-text / " (:prompt ctx))))))

(deftest act-gate-test
  (testing "noul over threshold"
    (is (true? (agent/act? {:decision {:type :noul :threshold 0.8}} {"act" {"type" "noul" "noul" 0.94}})))
    (is (false? (agent/act? {:decision {:type :noul :threshold 0.8}} {"act" {"type" "noul" "noul" 0.5}}))))
  (testing "choice in the act set"
    (is (true? (agent/act? {:decision {:type :choice :act ["alert" "log"]}} {"act" {"type" "choice" "choice" "alert"}})))
    (is (false? (agent/act? {:decision {:type :choice :act ["alert"]}} {"act" {"type" "choice" "choice" "log"}}))))
  (testing "score at least min"
    (is (true? (agent/act? {:decision {:type :score :min 2}} {"act" {"type" "score" "score" 3.0}})))
    (is (false? (agent/act? {:decision {:type :score :min 2}} {"act" {"type" "score" "score" 1.0}}))))
  (testing "no answer settles it"
    (is (nil? (agent/act? {:decision {:type :noul}} {})))))

(deftest resolve-code-test
  (is (fn? (agent/resolve-code (fn [] 1))))
  (is (nil? (agent/resolve-code nil)))
  (is (fn? (agent/resolve-code 'clojure.core/inc)))
  (is (thrown? Exception (agent/resolve-code "no.such.ns/thing"))))

(deftest decision-questions-test
  (is (= {"act" {"type" "noul" "instructions" "x"}}
         (agent/decision-questions {:decision {:type :noul :instructions "x"}})))
  (is (= {"q" {"type" "noul" "instructions" "y"}}
         (agent/decision-questions {:decision {:questions {"q" {"type" "noul" "instructions" "y"}}}}))))
