(ns overseer.server-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [overseer.agent :as agent]
            [overseer.memory :as mem]
            [overseer.server :as server]))

(defn- system [config]
  (agent/attach-manager
   (agent/make-system
    {:engine (fn [_ questions & _]
               {"answers" (into {} (map (fn [[q _]] [q {"type" "noul" "noul" 0.9}]) questions))})
     :sensors (atom {:clip {:id :clip :kind :text :capture (fn [_] "copied")}})
     :tools (atom {:append-memory {:name "append-memory"
                                   :fn (fn [s c] (mem/append-memory! (:store s) (:agent-id s) c))}})
     :agents (atom {})
     :store (mem/make-store)
     :config config})))

(defn- req [handler method uri & [body headers]]
  (let [r (handler {:request-method method :uri uri :headers (or headers {})
                    :body (when body (java.io.ByteArrayInputStream. (.getBytes (json/write-str body))))})]
    [(:status r) (json/read-str (:body r))]))

(deftest agent-crud-test
  (let [h (server/handler (system {}))
        agent-json {"id" "w" "prompt" "clip $CLIP"
                    "decision" {"type" "noul" "instructions" "act?" "threshold" 0.5}
                    "code" "(fn [ctx] (when (:act? ctx) ((:call ctx) \"append-memory\" \"hit;\")))"}]
    (is (= 201 (first (req h :post "/agents" agent-json))))
    (is (= 409 (first (req h :post "/agents" agent-json))))
    (is (= 422 (first (req h :post "/agents" (assoc agent-json "id" "x" "code" "(fn [")))))
    (is (= 422 (first (req h :post "/agents" (assoc agent-json "id" "x" "sensors" ["nope"])))))
    (let [[s body] (req h :get "/agents/w")]
      (is (= 200 s))
      (is (= ["clip"] (get body "captures"))))
    (let [[s body] (req h :post "/agents/w/run")]
      (is (= 200 s))
      (is (= "clip copied" (get body "state")))
      (is (true? (get body "act"))))
    (is (= "hit;" (get (second (req h :get "/memory/w")) "memory")))
    (is (= 1 (count (get (second (req h :get "/agents/w/runs")) "runs"))))
    (is (= 200 (first (req h :put "/agents/w" (assoc agent-json "prompt" "edited $CLIP")))))
    (is (= "edited copied" (get (second (req h :post "/agents/w/run")) "state")))
    (is (= 200 (first (req h :delete "/agents/w"))))
    (is (= 404 (first (req h :get "/agents/w"))))
    (is (= "" (get (second (req h :get "/memory/w")) "memory")))))

(deftest errors-and-auth-test
  (let [h (server/handler (system {:api-key "k"}))
        auth {"authorization" "Bearer k"}]
    (is (= 401 (first (req h :get "/agents"))))
    (is (= 200 (first (req h :get "/health"))))
    (is (= 200 (first (req h :get "/agents" nil auth))))
    (is (= 404 (first (req h :post "/agents/nope/run" nil auth))))
    (is (= 404 (first (req h :post "/agents/nope/start" nil auth))))
    (is (= 404 (first (req h :post "/capture/nope" nil auth))))
    (is (= 422 (first (req h :post "/decide" {"state" "x"} auth))))
    (is (= 200 (first (req h :post "/decide" {"state" "x" "questions" {"a" {"type" "noul"}}} auth))))
    (testing "malformed JSON is a 400"
      (is (= 400 (:status (h {:request-method :post :uri "/agents" :headers auth
                              :body (java.io.ByteArrayInputStream. (.getBytes "nope"))})))))))

(deftest memory-routes-test
  (let [h (server/handler (system {}))]
    (is (= "a" (get (second (req h :put "/memory/m" {"memory" "a"})) "memory")))
    (is (= "ab" (get (second (req h :post "/memory/m/append" {"memory" "b"})) "memory")))
    (is (= ["/x.png"] (get (second (req h :post "/memory/m/append" {"imemory" ["/x.png"]})) "imemory")))
    (is (= "" (get (second (req h :delete "/memory/m")) "memory")))))
