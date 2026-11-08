(ns overseer.agent
  "The micro-agent: a definition, a registry, and the runtime loop.

  An agent is a map:

    {:id :focus-tracker                 ; keyword id
     :name \"Focus Tracker\"              ; shown by `overseer list`
     :description \"...\"
     :prompt \"... $SCREEN $MEMORY\"       ; text with $SENSOR placeholders
     :sensors [:screen :clipboard]       ; sensors captured each pass
     :decision {:type :noul              ; a Lev question, or
                :instructions \"...\"       ; {:questions {qid qdef}} for several
                :threshold 0.8}           ; gate: act when noul > threshold
     :code (fn [ctx] ...)                ; the action fn (or a symbol resolved on use)
     :interval-ms 60000}

  The loop, every `:interval-ms`: capture the sensors, render the prompt
  into the Lev `state`, ask the decision engine, then call `:code` with a
  ctx carrying the answers, the captures, the memory, and the system.

  `:code` receives ctx with:
    :agent-id :decision :response :captured :images :screen :camera
    :clipboard :microphone :screen-audio :all-audio :memory :imemory
    :prompt :act? :system :tools"
  (:require [overseer.decision :as decision]
            [overseer.memory :as memory]
            [overseer.sensor :as sensor]
            [overseer.tool :as tool]))

;; The process-wide agent registry {id agent-def}.
(defonce registry (atom {}))

;; --- registry -----------------------------------------------------------------------

(defn- norm-id [id] (if (keyword? id) id (keyword (str id))))

(defn validate!
  "Check an agent def has the fields the loop needs; throws ex-info
  {:type :invalid-agent} otherwise."
  [a]
  (when-not (keyword? (:id a))
    (throw (ex-info "an agent needs a keyword :id" {:type :invalid-agent :agent a})))
  (when-not (string? (:prompt a))
    (throw (ex-info (str "agent " (:id a) " needs a string :prompt") {:type :invalid-agent :agent a})))
  a)

(defn register-agent!
  "Add an agent to a registry (the global one by default)."
  ([a] (register-agent! registry a))
  ([reg a]
   (let [a (validate! a)]
     (swap! reg assoc (norm-id (:id a)) a)
     a)))

(defn unregister-agent! [id] (swap! registry dissoc (norm-id id)))

(defn agents ([] (vals @registry)) ([reg] (vals @reg)))

(defn get-agent
  "The registered agent definition for `id`, or nil."
  ([id] (get-agent registry id))
  ([reg id] (get reg (norm-id id))))

;; --- code resolution -----------------------------------------------------------------

(defn resolve-code
  "An agent's :code as a callable fn: a fn passes through, a symbol (or
  string) is resolved with requiring-resolve."
  [code]
  (cond
    (fn? code) code
    (symbol? code) (let [v (requiring-resolve code)]
                     (or (when v @v)
                         (throw (ex-info (str "cannot resolve agent code " code)
                                         {:type :invalid-agent :code code}))))
    (string? code) (resolve-code (symbol code))
    (nil? code) nil
    :else (throw (ex-info (str "an agent's :code must be a fn or a resolvable symbol, got " (pr-str code))
                          {:type :invalid-agent :code code}))))

;; --- the decision --------------------------------------------------------------------

(defn decision-questions
  "The Lev question map an agent's :decision stands for. A :questions map
  passes through; otherwise the single question is built from
  :type/:instructions/:criteria under the id \"act\"."
  [{:keys [decision]}]
  (if (and (map? decision) (seq (:questions decision)))
    (:questions decision)
    {"act" (decision/default-question decision)}))

(defn act?
  "The act gate over the agent's :decision and the answered questions:
  a noul over :threshold (0.8), a choice whose option is in :act, a score
  at least :min (2). nil when the answers don't settle it."
  [agent answers]
  (let [d (:decision agent)
        a (get answers "act")]
    (when a
      (case (get a "type")
        "noul" (let [t (or (:threshold d) 0.8)] (> (decision/noul-of a) t))
        "choice" (when-let [opts (:act d)] (contains? (set (map str opts)) (str (decision/choice-of a))))
        "score" (let [mn (or (:min d) 2)] (>= (decision/score-of a) mn))
        false))))

;; --- one pass -------------------------------------------------------------------------

(defn capture
  "Capture the agent's sensors: {id -> value}."
  [system agent]
  (sensor/capture-many (:sensors system) (or (:sensors agent) [])
                       {:store (:store system)
                        :memory (:store system)
                        :agent-id (:id agent)
                        :transcribe (get-in (:config system) [:audio :transcribe])}))

(defn build-ctx
  "Assemble the ctx the agent's :code receives from one pass's captures
  and decision result. `state` is the rendered prompt the model saw."
  [system agent captures result state]
  (let [id (:id agent)
        answers (decision/answers result)
        store (:store system)
        tools (:tools system)
        images (filterv #(and (map? %) (= :image (:type %))) (vals captures))]
    {:agent-id id
     :decision answers
     :response nil
     :captured captures
     :images images
     :screen (get captures :screen)
     :camera (get captures :camera)
     :clipboard (get captures :clipboard)
     :microphone (get captures :microphone)
     :screen-audio (get captures :screen-audio)
     :all-audio (get captures :all-audio)
     :memory (memory/memory store id)
     :imemory (memory/image-memory store id)
     :prompt state
     :template (:prompt agent)
     :act? (act? agent answers)
     :system system
     :tools tools
     ;; (call "tool" arg ...) runs a tool with the current agent as the
     ;; default agentId
     :call (fn [name & args] (apply tool/call tools (assoc system :agent-id id) name args))}))

(defn run-once
  "One pass of the agent loop: capture, render the prompt into the state,
  ask the decision engine, run :code. Answers the :code result (or the ctx
  when there is no :code)."
  [system agent]
  (let [captures (capture system agent)
        state (sensor/render (:prompt agent) captures {:memory (:store system) :agent-id (:id agent)})
        result ((:decide system) state (decision-questions agent))
        ctx (build-ctx system agent captures result state)]
    (when-let [code (:code agent)]
      ((resolve-code code) ctx))
    ctx))

;; --- the runtime ---------------------------------------------------------------------

(defn make-system
  "Assemble the running system from its parts. `engine` is a decision
  engine fn (see overseer.decision/make-engine); `sensors` and `tools` are
  registry maps; `store` an atom or nil (fresh); `config` the config map."
  [{:keys [engine sensors tools store config]}]
  {:store (or store (memory/make-store))
   :sensors (or sensors {})
   :tools (or tools {})
   :decide engine
   :config (or config {})
   :running (atom {})
   :threads (atom {})})

(defn running?
  "Is the agent's loop thread live?"
  [system agent-id]
  (boolean (get @(:running system) (norm-id agent-id))))

(defn run-now!
  "Run one pass now; the agent must be registered. Answers the ctx."
  [system agent-id]
  (let [a (or (get-agent agent-id)
              (throw (ex-info (str "no agent " agent-id) {:type :unknown-agent :id agent-id})))]
    (run-once system a)))

(defn start!
  "Start the agent's loop thread; no-op if already running. Answers agent-id."
  [system agent-id]
  (let [id (norm-id agent-id)
        a (or (get-agent id)
              (throw (ex-info (str "no agent " id) {:type :unknown-agent :id id})))]
    (when-not (running? system id)
      (swap! (:running system) assoc id true)
      (let [interval (long (or (:interval-ms a) 60000))
            thread (Thread.
                    (fn []
                      (loop []
                        (when (get @(:running system) id)
                          (try (run-once system a)
                               (catch Exception e
                                 (binding [*out* *err*]
                                   (println "overseer: agent" id "failed:" (ex-message e)))))
                          (Thread/sleep interval)
                          (recur)))))]
        (.start thread)
        (swap! (:threads system) assoc id thread)))
    id))

(defn stop!
  "Stop the agent's loop thread; no-op if not running. Answers agent-id."
  [system agent-id]
  (let [id (norm-id agent-id)]
    (swap! (:running system) dissoc id)
    (when-let [t (get @(:threads system) id)]
      (.interrupt t))
    (swap! (:threads system) dissoc id)
    id))

(defn stop-all! [system]
  (doseq [id (keys @(:running system))] (stop! system id)))

(defn start-all!
  "Start every registered agent. Answers the ids."
  [system]
  (mapv #(start! system (:id %)) (agents)))

(defn manager
  "The handle tools use to start/stop and read agents from a system."
  [system]
  {:start #(start! system %)
   :stop #(stop! system %)
   :agent #(get-agent %)
   :agents #(agents)})

(defn attach-manager
  "A system with its :manager handle set."
  [system]
  (assoc system :manager (manager system)))
