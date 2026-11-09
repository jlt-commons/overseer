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

(deftest registry-lookup-test
  ;; get-agent must read through the registry atom (it used to answer nil)
  (let [reg (atom {})]
    (agent/register-agent! reg {:id :a :prompt "p"})
    (is (= :a (:id (agent/get-agent reg :a))))
    (is (= :a (:id (agent/get-agent reg "a"))))
    (is (= 1 (count (agent/agents reg))))
    (agent/unregister-agent! reg :a)
    (is (nil? (agent/get-agent reg :a)))))

(deftest validate-test
  (is (thrown? Exception (agent/validate! {:id :a})))
  (is (thrown? Exception (agent/validate! {:id :a :prompt "p" :decision {:type :bogus}})))
  (is (thrown? Exception (agent/validate! {:id :a :prompt "p" :interval-ms -1})))
  (is (thrown? Exception (agent/validate! {:id (keyword "a b") :prompt "p"})))
  (is (map? (agent/validate! {:id :a :prompt "p"}))))

(deftest default-decision-test
  ;; an agent without a :decision asks a noul
  (is (= "noul" (get-in (agent/decision-questions {:prompt "p"}) ["act" "type"]))))

(deftest resolve-source-code-test
  (let [f (agent/resolve-code "(fn [ctx] (inc (:n ctx)))")]
    (is (= 2 (f {:n 1})))
    (is (identical? f (agent/resolve-code "(fn [ctx] (inc (:n ctx)))"))))
  (is (= inc (agent/resolve-code "clojure.core/inc")))
  (is (nil? (agent/resolve-code "  ")))
  (is (thrown? Exception (agent/resolve-code "(fn [ctx")))
  (is (thrown? Exception (agent/resolve-code "42"))))

(deftest prompt-sensors-test
  (let [sensors {:screen {} :clipboard {} :memory {} :screen-audio {}}]
    (is (= [:clipboard :screen-audio]
           (agent/prompt-sensors sensors {:prompt "$CLIPBOARD $SCREEN_AUDIO $MEMORY $HOME"})))
    (is (= [:screen :clipboard]
           (agent/prompt-sensors sensors {:prompt "$CLIPBOARD" :sensors [:screen]})))))

(deftest noul-trend-test
  (let [trends (atom {})
        step (fn [n] (get (agent/with-noul-trend trends :a {"act" {"type" "noul" "noul" n}}) "act"))]
    (is (false? (get (step 0.9) "dropped")))
    (step 0.9)
    (step 0.9)
    (is (= 0.9 (get (step 0.6) "start")))
    (is (false? (get (step 0.6) "dropped")))
    (is (true? (get (step 0.6) "dropped")))
    ;; back up resets the run of low passes
    (is (false? (get (step 0.9) "dropped"))))
  (testing "several questions pass through"
    (let [answers {"a" {"type" "noul" "noul" 0.1} "b" {"type" "noul" "noul" 0.2}}]
      (is (= answers (agent/with-noul-trend (atom {}) :a answers))))))

(deftest run-history-and-only-on-change-test
  (let [calls (atom 0)
        system (test-system (fn [_ q] (swap! calls inc)
                              {"answers" {"act" {"type" "noul" "noul" 0.9}}}))
        a {:id :t :prompt "$SCREEN" :only-on-change true}]
    (agent/run-once system a)
    (agent/run-once system a)
    (is (= 1 @calls))
    (let [[newest oldest] (agent/runs system :t)]
      (is (true? (:cached newest)))
      (is (false? (:cached oldest)))
      (is (= "screen-text" (:state newest))))
    (testing "a failing pass is recorded and rethrown"
      (is (thrown? Exception (agent/run-once system (assoc a :id :bad :code (fn [_] (throw (ex-info "boom" {})))))))
      (is (= "boom" (:error (first (agent/runs system :bad))))))))

(deftest loop-test
  (let [reg (atom {})
        passes (atom 0)
        system (agent/attach-manager
                (agent/make-system
                 {:engine (fake-engine {"type" "noul" "noul" 0.9})
                  :sensors {} :tools {} :agents reg}))]
    (agent/register-agent! reg {:id :l :prompt "p" :interval-ms 20
                                :code (fn [_] (swap! passes inc))})
    (is (thrown? Exception (agent/start! system :missing)))
    (agent/start! system :l)
    (agent/start! system :l)                 ; no second thread
    (Thread/sleep 200)
    (is (agent/running? system :l))
    (agent/stop! system :l)
    (Thread/sleep 50)
    (let [n @passes]
      (is (pos? n))
      (Thread/sleep 100)
      (is (= n @passes) "no passes after stop")
      (is (not (agent/running? system :l))))
    (testing "an agent can stop itself"
      (agent/register-agent! reg {:id :self :prompt "p" :interval-ms 10
                                  :code (fn [ctx] ((:stop (:manager (:system ctx))) :self))})
      (agent/start! system :self)
      (Thread/sleep 100)
      (is (not (agent/running? system :self))))))
