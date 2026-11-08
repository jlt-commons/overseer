(ns overseer.server
  "The overseer HTTP API, built on ruuter and ring-chez-adapter (the same
  stack Lev's own server uses).

  Routes:
    GET  /health              status + agent/sensor/tool counts
    GET  /agents              the registered agents (id, name, description)
    GET  /agents/:id          one agent
    POST /agents/:id/start    start its loop
    POST /agents/:id/stop     stop its loop
    POST /agents/:id/run      run one pass now, answer the ctx
    GET  /sensors             the registered sensors
    GET  /tools               the registered tools
    POST /capture/:sensor     capture one sensor
    POST /decide              {\"state\" .., \"questions\" {..}} through the engine
    GET  /memory/:agent       the agent's text memory
    PUT  /memory/:agent       set the agent's text memory

  The handler takes the running `system` (overseer.agent/make-system)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [overseer.agent :as agent]
            [overseer.memory :as memory]
            [overseer.sensor :as sensor]
            [ring-chez.adapter :as adapter]
            [ruuter.core :as ruuter]))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (str (json/write-str body) "\n")})

(defn- ok [body] (json-response 200 body))
(def ^:private not-found (json-response 404 {"error" "not found"}))
(def ^:private method-not-allowed (json-response 405 {"error" "method not allowed"}))

(defn- parse-body
  "The JSON object in the request body; {} when empty."
  [req]
  (let [b (:body req)]
    (if (str/blank? b)
      {}
      (json/read-str b))))

(defn- describe-agent [system a]
  (let [id (:id a)]
    {:id (subs (str id) 1)
     :name (:name a)
     :description (:description a)
     :sensors (mapv (comp #(subs % 1) str) (or (:sensors a) []))
     :interval-ms (:interval-ms a)
     :running (boolean (get @(:running system) id))}))

(defn- describe-sensor [[id s]]
  {(subs (str id) 1) {:description (:description s) :kind (name (:kind s :text))}})

(defn- describe-tool [[n t]]
  {(subs (str n) 1) (:doc t)})

(defn- health [system]
  (ok {:status "ok"
       :model "overseer"
       :agents (count (agent/agents))
       :sensors (count (:sensors system))
       :tools (count (:tools system))
       :running (mapv (comp #(subs % 1) str) (keys @(:running system)))}))

(defn- decide
  [system body]
  (let [state (get body "state")
        questions (get body "questions")]
    (when-not (and (some? state) (map? questions))
      (throw (ex-info "decide needs {\"state\" .., \"questions\" {..}}"
                      {:type :invalid-request})))
    (ok ((:decide system) state questions))))

(defn- capture [system sensor-id]
  (let [id (keyword sensor-id)
        v (sensor/capture (:sensors system) id
                          {:store (:store system)
                           :memory (:store system)
                           :agent-id nil
                           :transcribe (get-in (:config system) [:audio :transcribe])})]
    (if (and (map? v) (= :image (:type v)))
      (ok {:sensor sensor-id :kind "image" :mime (:mime v) :path (:path v)})
      (ok {:sensor sensor-id :kind "text" :value (when v (str v))}))))

(defn- routes [system]
  (let [real [{:path "/health" :method :get :response (fn [_] (health system))}
              {:path "/agents" :method :get
               :response (fn [_] (ok {:agents (mapv #(describe-agent system %) (agent/agents))}))}
              {:path "/agents/:id" :method :get
               :response (fn [req]
                           (let [id (keyword (get-in req [:params :id]))]
                             (if-let [a (agent/get-agent id)]
                               (ok (describe-agent system a))
                               not-found)))}
              {:path "/agents/:id/start" :method :post
               :response (fn [req] (ok {:started (subs (str (agent/start! system (get-in req [:params :id]))) 1)}))}
              {:path "/agents/:id/stop" :method :post
               :response (fn [req] (ok {:stopped (subs (str (agent/stop! system (get-in req [:params :id]))) 1)}))}
              {:path "/agents/:id/run" :method :post
               :response (fn [req]
                           (let [id (keyword (get-in req [:params :id]))
                                 ctx (agent/run-now! system id)]
                             (ok {:agent (subs (str id) 1)
                                  :decision (:decision ctx)
                                  :act? (:act? ctx)})))}
              {:path "/sensors" :method :get
               :response (fn [_] (ok {:sensors (into {} (map describe-sensor (:sensors system)))}))}
              {:path "/tools" :method :get
               :response (fn [_] (ok {:tools (into {} (map describe-tool (:tools system)))}))}
              {:path "/capture/:sensor" :method :post
               :response (fn [req] (capture system (get-in req [:params :sensor])))}
              {:path "/decide" :method :post
               :response (fn [req] (try (decide system (parse-body req))
                                        (catch Exception e
                                          (if (#{:model-unavailable :invalid-request} (:type (ex-data e)))
                                            (json-response (if (= :model-unavailable (:type (ex-data e))) 503 422)
                                                           {"error" (ex-message e)})
                                            (throw e)))))}
              {:path "/memory/:agent" :method :get
               :response (fn [req]
                           (let [id (keyword (get-in req [:params :agent]))]
                             (ok {:agent (subs (str id) 1)
                                  :memory (memory/memory (:store system) id)
                                  :imemory (mapv #(if (map? %) (:path %) %) (memory/image-memory (:store system) id))})))}
              {:path "/memory/:agent" :method :put
               :response (fn [req]
                           (let [id (keyword (get-in req [:params :agent]))
                                 b (parse-body req)]
                             (memory/set-memory! (:store system) id (get b "memory" ""))
                             (ok {:agent (subs (str id) 1)
                                  :memory (memory/memory (:store system) id)})))}]
        disallowed (for [{:keys [path method]} real
                         m [:get :post :put :delete :patch :head :options]
                         :when (not= m method)]
                     {:path path :method m :response method-not-allowed})]
    (vec (concat real disallowed [{:path :not-found :response not-found}]))))

(defn handler
  "A ring handler for the API, given the running `system`."
  [system]
  (let [table (ruuter/compile-routes (routes system))]
    (fn [req] (ruuter/route table req))))

(defn start
  "Run the API on ring-chez-adapter; answers the server handle.
  opts: :port (8080), :host (\"127.0.0.1\")."
  [system {:keys [port host]
           :or {port 8080 host "127.0.0.1"}}]
  (adapter/run-server
   (handler system)
   {:on-failure (fn [_ ex]
                  (binding [*out* *err*] (println "overseer.server:" (ex-message ex)))
                  (json-response 500 {"error" "Internal Server Error"}))
    :port port :host host}))

(defn stop [server]
  (adapter/stop-server server))
