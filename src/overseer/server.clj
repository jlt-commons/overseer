(ns overseer.server
  "The overseer HTTP API, built on ruuter and ring-chez-adapter (the same
  stack Lev's own server uses).

  Routes:
    GET    /health                   status + agent/sensor/tool counts
    GET    /agents                   the registered agents
    POST   /agents                   create an agent (JSON, see overseer.library)
    GET    /agents/:id               one agent, with its prompt, decision and code
    PUT    /agents/:id               replace an agent's definition (stop it first)
    DELETE /agents/:id               stop and delete an agent and its memory
    POST   /agents/:id/start         start its loop
    POST   /agents/:id/stop          stop its loop
    POST   /agents/:id/run           run one pass now, answer the run
    GET    /agents/:id/runs          recent passes, newest first (?limit=N)
    GET    /sensors                  the registered sensors
    POST   /sensors                  add a command sensor {\"id\" \"cmd\" [..] \"kind\"}
    DELETE /sensors/:id              remove a command sensor
    POST   /capture/:sensor          capture one sensor
    GET    /tools                    the registered tools
    POST   /decide                   {\"state\" .., \"questions\" {..}} through the engine
    GET    /memory/:agent            the agent's text and image memory
    PUT    /memory/:agent            {\"memory\" ..} and/or {\"imemory\" [paths]} to replace
    POST   /memory/:agent/append     {\"memory\" ..} and/or {\"imemory\" [paths]} to append
    DELETE /memory/:agent            clear the agent's memory

  With an :api-key, every route but /health needs `Authorization: Bearer
  <key>`. Errors answer {\"error\" msg}: 400 bad JSON, 401 auth, 404
  unknown agent/sensor, 409 conflict, 422 invalid request, 503 no model.

  The handler takes the running `system` (overseer.agent/make-system)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [overseer.agent :as agent]
            [overseer.library :as lib]
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

(defn- fail [type msg & [data]] (throw (ex-info msg (merge {:type type} data))))

(def ^:private status-of
  {:invalid-json 400
   :unauthorized 401
   :unknown-agent 404
   :unknown-sensor 404
   :conflict 409
   :invalid-request 422
   :invalid-agent 422
   :invalid-sensor 422
   :invalid-decision 422
   :unknown-tool 422
   :model-unavailable 503})

(defn- body-string [body]
  (cond (nil? body) "" (string? body) body :else (slurp body)))

(defn- parse-body
  "The JSON object in the request body; {} when empty."
  [req]
  (let [b (body-string (:body req))]
    (if (str/blank? b)
      {}
      (let [v (try (json/read-str b)
                   (catch Exception e (fail :invalid-json (str "invalid JSON: " (ex-message e)))))]
        (when-not (map? v) (fail :invalid-json "the body must be a JSON object"))
        v))))

(defn- query-param [req k]
  (some (fn [kv] (let [[a b] (str/split kv (re-pattern "=") 2)] (when (= a k) b)))
        (str/split (or (:query-string req) "") (re-pattern "&"))))

(defn- param [req k] (get-in req [:params k]))

(declare tool-map)

;; --- agents ---------------------------------------------------------------------------

(defn- the-agent [system id]
  (or (agent/get-agent (:agents system) id)
      (fail :unknown-agent (str "no agent " id))))

(defn- summary [system a]
  (select-keys (lib/agent->json system a) [:id :name :description :sensors :captures :interval_ms :running :editable]))

(defn- run-response [system id ctx]
  (let [r (first (agent/runs system id 1))]
    (merge {:agent (agent/id-str id)} r
           {:result (let [v (:result ctx)] (when (or (string? v) (number? v) (boolean? v)) v))})))

(defn- create-agent [system body]
  (let [a (lib/json->agent body)]
    (when (agent/get-agent (:agents system) (:id a))
      (fail :conflict (str "agent " (name (:id a)) " exists; PUT /agents/" (name (:id a)) " to edit it")))
    (lib/check-agent! system a)
    (json-response 201 (lib/agent->json system (lib/save-agent! system a)))))

(defn- edit-agent [system id body]
  (let [existing (the-agent system id)
        a (lib/json->agent (assoc body "id" id) id)]
    (when (agent/running? system (:id existing))
      (fail :conflict (str "agent " id " is running: stop it, edit it, then start it again")))
    (lib/check-agent! system a)
    (ok (lib/agent->json system (lib/save-agent! system a)))))

(defn- delete-agent [system id]
  (let [a (the-agent system id)]
    (lib/delete-agent! system (:id a))
    (memory/delete! (:store system) (:id a))
    (ok {:deleted id})))

(defn- run-agent [system id]
  (let [a (the-agent system id)
        ;; a failing decision or code is recorded in the run's "error"
        ctx (try (agent/run-now! system (:id a)) (catch Exception _ nil))]
    (ok (run-response system (:id a) ctx))))

;; --- sensors ---------------------------------------------------------------------------

(defn- describe-sensor [[id s]]
  [(name id) (cond-> {:description (:description s)
                      :kind (name (:kind s :text))
                      :placeholder (sensor/placeholder id)}
               (:cmd s) (assoc :cmd (mapv #(if (= :file %) "file" %) (:cmd s))))])

(defn- sensors-map [system] (into (sorted-map) (map describe-sensor (sensor/all (:sensors system)))))

(defn- add-sensor [system body]
  (let [spec (lib/json->sensor body)
        existing (sensor/get-sensor (:sensors system) (:id spec))]
    (when (and existing (not (:cmd existing)))
      (fail :conflict (str "sensor " (name (:id spec)) " is a built-in or plugin sensor")))
    (let [s (lib/save-sensor! system spec)]
      (json-response 201 (into {} [(describe-sensor [(:id s) s])])))))

(defn- delete-sensor [system id]
  (let [s (or (sensor/get-sensor (:sensors system) (keyword id))
              (fail :unknown-sensor (str "no sensor " id)))]
    (when-not (:cmd s)
      (fail :conflict (str "sensor " id " is a built-in or plugin sensor and cannot be deleted")))
    (lib/delete-sensor! system id)
    (ok {:deleted id})))

(defn- capture [system sensor-id]
  (let [id (keyword sensor-id)
        _ (when-not (sensor/get-sensor (:sensors system) id)
            (fail :unknown-sensor (str "no sensor " sensor-id)))
        v (sensor/capture (:sensors system) id (agent/capture-ctx system nil))]
    (cond
      (and (map? v) (= :image (:type v)))
      (ok {:sensor sensor-id :kind "image" :mime (:mime v) :path (:path v)})
      (and (map? v) (= :error (:type v)))
      (ok {:sensor sensor-id :kind "error" :error (:message v)})
      :else (ok {:sensor sensor-id :kind "text" :value (when v (sensor/render-value v))}))))

;; --- memory ----------------------------------------------------------------------------

(defn- image-paths [v]
  (when (some? v)
    (when-not (and (sequential? v) (every? string? v))
      (fail :invalid-request "\"imemory\" must be a list of image file paths"))
    (mapv (fn [p] {:type :image
                   :mime (if (re-find (re-pattern "(?i)\\.jpe?g$") p) "image/jpeg" "image/png")
                   :path p})
          v)))

(defn- memory-view [system id]
  {:agent id
   :memory (memory/memory (:store system) id)
   :imemory (mapv #(if (map? %) (:path %) %) (memory/image-memory (:store system) id))})

(defn- write-memory [system id body append?]
  (let [store (:store system)
        imgs (image-paths (get body "imemory"))]
    (when (contains? body "memory")
      ((if append? memory/append-memory! memory/set-memory!) store id (str (get body "memory"))))
    (when imgs
      ((if append? memory/append-image-memory! memory/set-image-memory!) store id imgs))
    (ok (memory-view system id))))

;; --- misc ------------------------------------------------------------------------------

(defn- health [system]
  (ok {:status "ok"
       :model "overseer"
       :agents (count (agent/system-agents system))
       :sensors (count (sensor/all (:sensors system)))
       :tools (count (tool-map system))
       :running (mapv agent/id-str (keys @(:running system)))}))

(defn- decide [system body]
  (let [state (get body "state")
        questions (get body "questions")]
    (when-not (and (string? state) (map? questions) (seq questions))
      (fail :invalid-request "decide needs {\"state\" \"..\", \"questions\" {id {\"type\" ..}}}"))
    (let [opts (into {} (keep (fn [k] (when (contains? body k) [(keyword k) (get body k)]))
                              ["model" "constraints" "thinking"]))]
      (ok (if (seq opts)
            ((:decide system) state questions opts)
            ((:decide system) state questions))))))

(defn- tool-map [system]
  (let [t (:tools system)] (if (map? t) t @t)))

(defn- describe-tool [[n t]] [(name n) (:doc t)])

(defn- routes [system]
  (let [h (fn [f] (fn [req] (f req)))
        real [{:path "/health" :method :get :response (h (fn [_] (health system)))}
              {:path "/agents" :method :get
               :response (h (fn [_] (ok {:agents (mapv #(summary system %)
                                                       (sort-by :id (agent/system-agents system)))})))}
              {:path "/agents" :method :post
               :response (h (fn [req] (create-agent system (parse-body req))))}
              {:path "/agents/:id" :method :get
               :response (h (fn [req] (ok (lib/agent->json system (the-agent system (param req :id))))))}
              {:path "/agents/:id" :method :put
               :response (h (fn [req] (edit-agent system (param req :id) (parse-body req))))}
              {:path "/agents/:id" :method :delete
               :response (h (fn [req] (delete-agent system (param req :id))))}
              {:path "/agents/:id/start" :method :post
               :response (h (fn [req] (let [a (the-agent system (param req :id))]
                                        (agent/start! system (:id a))
                                        (ok {:started (param req :id)}))))}
              {:path "/agents/:id/stop" :method :post
               :response (h (fn [req] (let [a (the-agent system (param req :id))]
                                        (agent/stop! system (:id a))
                                        (ok {:stopped (param req :id)}))))}
              {:path "/agents/:id/run" :method :post
               :response (h (fn [req] (run-agent system (param req :id))))}
              {:path "/agents/:id/runs" :method :get
               :response (h (fn [req]
                              (let [a (the-agent system (param req :id))
                                    limit (or (some-> (query-param req "limit") parse-long) 20)]
                                (ok {:agent (param req :id)
                                     :runs (agent/runs system (:id a) limit)}))))}
              {:path "/sensors" :method :get
               :response (h (fn [_] (ok {:sensors (sensors-map system)})))}
              {:path "/sensors" :method :post
               :response (h (fn [req] (add-sensor system (parse-body req))))}
              {:path "/sensors/:id" :method :delete
               :response (h (fn [req] (delete-sensor system (param req :id))))}
              {:path "/tools" :method :get
               :response (h (fn [_] (ok {:tools (into (sorted-map) (map describe-tool (tool-map system)))})))}
              {:path "/capture/:sensor" :method :post
               :response (h (fn [req] (capture system (param req :sensor))))}
              {:path "/decide" :method :post
               :response (h (fn [req] (decide system (parse-body req))))}
              {:path "/memory/:agent" :method :get
               :response (h (fn [req] (ok (memory-view system (param req :agent)))))}
              {:path "/memory/:agent" :method :put
               :response (h (fn [req] (write-memory system (param req :agent) (parse-body req) false)))}
              {:path "/memory/:agent/append" :method :post
               :response (h (fn [req] (write-memory system (param req :agent) (parse-body req) true)))}
              {:path "/memory/:agent" :method :delete
               :response (h (fn [req]
                              (memory/delete! (:store system) (param req :agent))
                              (ok (memory-view system (param req :agent)))))}]
        methods (group-by :path real)
        disallowed (for [[path rs] methods
                         m [:get :post :put :delete :patch :head :options]
                         :when (not-any? #(= m (:method %)) rs)]
                     {:path path :method m :response method-not-allowed})]
    (vec (concat real disallowed [{:path :not-found :response not-found}]))))

(defn- authorized? [api-key req]
  (or (nil? api-key)
      (= "/health" (:uri req))
      (= (str "Bearer " api-key)
         (or (get-in req [:headers "authorization"]) (get-in req [:headers "Authorization"])))))

(defn handler
  "A ring handler for the API, given the running `system`. Requests need
  the system's :api-key (config) as a bearer token when one is set."
  [system]
  (let [table (ruuter/compile-routes (routes system))
        api-key (get-in system [:config :api-key])]
    (fn [req]
      (if-not (authorized? api-key req)
        (json-response 401 {"error" "unauthorized: send Authorization: Bearer <api-key>"})
        (try (ruuter/route table req)
             (catch Exception e
               (if-let [status (status-of (:type (ex-data e)))]
                 (json-response status {"error" (ex-message e)})
                 (throw e))))))))

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
