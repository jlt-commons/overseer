(ns overseer.agent
  "The micro-agent: a definition, a registry, and the runtime loop.

  An agent is a map:

    {:id :focus-tracker                 ; keyword id
     :name \"Focus Tracker\"              ; shown by `overseer list`
     :description \"...\"
     :prompt \"... $SCREEN $MEMORY\"       ; text with $SENSOR placeholders
     :sensors [:screen :clipboard]       ; extra sensors to capture (the prompt's
                                         ; placeholders are captured as well)
     :decision {:type :noul              ; a Lev question, or
                :instructions \"...\"       ; {:questions {qid qdef}} for several
                :threshold 0.8}           ; gate: act when noul > threshold
     :code (fn [ctx] ...)                ; the action: a fn, a symbol resolved on
                                         ; use, or Jolt source \"(fn [ctx] ...)\"
     :interval-ms 60000
     :only-on-change false}              ; reuse the last decision while the
                                         ; rendered state is unchanged

  The loop, every `:interval-ms`: capture the sensors, render the prompt
  into the Lev `state`, ask the decision engine, then call `:code` with a
  ctx carrying the answers, the captures, the memory, and the system.
  Every pass is recorded in the system's run history (see `runs`).

  `:code` receives ctx with:
    :agent-id :decision :response :captured :images :screen :camera
    :clipboard :microphone :screen-audio :all-audio :memory :imemory
    :prompt :act? :system :tools :call"
  (:require [clojure.string :as str]
            [overseer.decision :as decision]
            [overseer.memory :as memory]
            [overseer.sensor :as sensor]
            [overseer.tool :as tool]))

;; The process-wide agent registry {id agent-def}.
(defonce registry (atom {}))

;; --- registry -----------------------------------------------------------------------

(defn norm-id [id] (if (keyword? id) id (keyword (str id))))

(defn id-str
  "An agent id as a plain string: :focus-tracker -> \"focus-tracker\"."
  [id]
  (if (keyword? id) (subs (str id) 1) (str id)))

(def ^:private id-re (re-pattern "[A-Za-z0-9_-]+"))

(defn validate!
  "Check an agent def has the fields the loop needs; throws ex-info
  {:type :invalid-agent} otherwise."
  [a]
  (when-not (and (keyword? (:id a)) (re-matches id-re (id-str (:id a))))
    (throw (ex-info "an agent needs a keyword :id of letters, digits, _ or -"
                    {:type :invalid-agent :agent (dissoc a :code)})))
  (when-not (string? (:prompt a))
    (throw (ex-info (str "agent " (id-str (:id a)) " needs a string :prompt")
                    {:type :invalid-agent :agent (dissoc a :code)})))
  (when (some? (:decision a))
    (when-not (map? (:decision a))
      (throw (ex-info (str "agent " (id-str (:id a)) " :decision must be a map")
                      {:type :invalid-agent})))
    (when-not (seq (:questions (:decision a)))
      (try (decision/default-question (:decision a))
           (catch Exception e
             (throw (ex-info (ex-message e) {:type :invalid-agent}))))))
  (when-let [ms (:interval-ms a)]
    (when-not (and (number? ms) (pos? ms))
      (throw (ex-info (str "agent " (id-str (:id a)) " :interval-ms must be a positive number")
                      {:type :invalid-agent}))))
  a)

(defn- reg-map [reg] (if (map? reg) reg @reg))

(defn register-agent!
  "Add an agent to a registry (the global one by default)."
  ([a] (register-agent! registry a))
  ([reg a]
   (let [a (validate! (update a :id norm-id))]
     (swap! reg assoc (:id a) a)
     a)))

(defn unregister-agent!
  ([id] (unregister-agent! registry id))
  ([reg id] (swap! reg dissoc (norm-id id))))

(defn agents ([] (agents registry)) ([reg] (vals (reg-map reg))))

(defn get-agent
  "The registered agent definition for `id`, or nil."
  ([id] (get-agent registry id))
  ([reg id] (get (reg-map reg) (norm-id id))))

;; --- code resolution -----------------------------------------------------------------

(defn- eval-source
  "Evaluate agent code given as Jolt source; it must evaluate to a fn."
  [src]
  (let [form (try (read-string src)
                  (catch Exception e
                    (throw (ex-info (str "cannot read agent code: " (ex-message e))
                                    {:type :invalid-agent}))))
        v (if (symbol? form)
            (let [v (requiring-resolve form)] (when v @v))
            (try (binding [*ns* (the-ns 'overseer.agent)] (eval form))
                 (catch Exception e
                   (throw (ex-info (str "cannot evaluate agent code: " (ex-message e))
                                   {:type :invalid-agent})))))]
    (when-not (fn? v)
      (throw (ex-info (str "agent code must be a fn, (fn [ctx] ...), got " (pr-str src))
                      {:type :invalid-agent :code src})))
    v))

(def ^:private code-cache (atom {}))

(defn resolve-code
  "An agent's :code as a callable fn: a fn passes through, a symbol is
  resolved with requiring-resolve, a string is Jolt source — a fn form
  such as \"(fn [ctx] ...)\" or a fully-qualified symbol. Strings are
  evaluated once and cached."
  [code]
  (cond
    (fn? code) code
    (symbol? code) (let [v (requiring-resolve code)]
                     (or (when v @v)
                         (throw (ex-info (str "cannot resolve agent code " code)
                                         {:type :invalid-agent :code code}))))
    (string? code) (if (str/blank? code)
                     nil
                     (or (get @code-cache code)
                         (let [f (eval-source code)]
                           (swap! code-cache assoc code f)
                           f)))
    (nil? code) nil
    :else (throw (ex-info (str "an agent's :code must be a fn, a resolvable symbol or source, got " (pr-str code))
                          {:type :invalid-agent :code code}))))

;; --- the decision --------------------------------------------------------------------

(defn decision-questions
  "The Lev question map an agent's :decision stands for. A :questions map
  passes through; otherwise the single question is built from
  :type/:instructions/:criteria under the id \"act\" (a noul when the
  agent has no :decision)."
  [{:keys [decision]}]
  (if (and (map? decision) (seq (:questions decision)))
    (:questions decision)
    {"act" (decision/default-question (merge {:type :noul} decision))}))

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

;; --- the noul trend ------------------------------------------------------------------
;; A watched job can end on a state that still looks a bit like the running
;; one, so noul settles mid-range rather than near 0. Staying well below
;; where the run started still means it ended (Observer's `start` /
;; `dropped`).

(def trend-start-iterations 3)  ; their mean noul is the run's start level
(def trend-drop 0.15)           ; this far below the start counts as down
(def trend-hold 3)              ; passes in a row down before `dropped`

(defn with-noul-trend
  "Add \"start\" and \"dropped\" to the single \"act\" noul answer, tracking
  the trend in `trends` (an atom {agent-id {:first [..] :down n}}). Other
  answers pass through."
  [trends agent-id answers]
  (let [a (get answers "act")]
    (if-not (and a (= "noul" (get a "type")) (= 1 (count answers)))
      answers
      (let [n (decision/noul-of a)
            t (get (swap! trends update agent-id
                          (fn [{firsts :first down :down :or {firsts [] down 0}}]
                            (if (< (count firsts) trend-start-iterations)
                              {:first (conj firsts n) :down 0}
                              (let [start (/ (reduce + firsts) (count firsts))]
                                {:first firsts
                                 :start start
                                 :down (if (< n (- start trend-drop)) (inc down) 0)}))))
                   agent-id)]
        (assoc answers "act"
               (cond-> (assoc a "dropped" (boolean (and (:start t) (>= (:down t) trend-hold))))
                 (:start t) (assoc "start" (:start t))))))))

(defn reset-trend! [system id]
  (when-let [t (:trends system)] (swap! t dissoc (norm-id id))))

;; --- one pass -------------------------------------------------------------------------

(def ^:private memory-sensors #{:memory :imemory})

(defn prompt-sensors
  "The sensor ids a pass captures: the agent's :sensors plus every
  registered sensor whose placeholder appears in the prompt (memory
  placeholders are rendered from the store, not captured)."
  [sensors agent]
  (let [known (sensor/all sensors)
        in-prompt (->> (re-seq sensor/placeholder-re (or (:prompt agent) ""))
                       (map (comp sensor/sensor-id-of second))
                       (filter #(contains? known %))
                       (remove memory-sensors))]
    (vec (distinct (concat (map keyword (or (:sensors agent) [])) in-prompt)))))

(defn capture-ctx
  "The context a sensor's :capture fn receives."
  [system agent-id]
  {:store (:store system)
   :memory (:store system)
   :agent-id agent-id
   :config (:config system)
   :transcribe (get-in (:config system) [:audio :transcribe])})

(defn capture
  "Capture the agent's sensors: {id -> value}."
  [system agent]
  (sensor/capture-many (:sensors system) (prompt-sensors (:sensors system) agent)
                       (capture-ctx system (:id agent))))

(defn- image? [v] (and (map? v) (= :image (:type v))))

(defn build-ctx
  "Assemble the ctx the agent's :code receives from one pass's captures
  and decision answers. `state` is the rendered prompt the model saw."
  [system agent captures answers state]
  (let [id (:id agent)
        store (:store system)
        tools (:tools system)
        imemory (memory/image-memory store id)
        images (vec (concat (filter image? (vals captures))
                            (mapcat #(when (sequential? %) (filter image? %)) (vals captures))
                            (when (re-find (re-pattern "\\$IMEMORY") (or (:prompt agent) ""))
                              (filter image? imemory))))]
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
     :imemory imemory
     :prompt state
     :template (:prompt agent)
     :act? (act? agent answers)
     :system system
     :tools tools
     ;; (call "tool" arg ...) runs a tool with the current agent as the
     ;; default agentId
     :call (fn [name & args] (apply tool/call tools (assoc system :agent-id id) name args))}))

(def max-runs "Runs kept per agent in the history." 100)

(defn- summarize-capture [v]
  (cond
    (image? v) {:type "image" :mime (:mime v) :path (:path v)}
    (and (map? v) (= :error (:type v))) {:type "error" :message (:message v)}
    (sequential? v) (mapv summarize-capture v)
    :else v))

(defn- record-run! [system id run]
  (when-let [runs (:runs system)]
    (swap! runs update id (fn [rs] (let [rs (conj (or rs []) run)]
                                     (if (> (count rs) max-runs) (subvec rs (- (count rs) max-runs)) rs)))))
  run)

(defn runs
  "The recorded passes of agent `id`, newest first (at most `limit`)."
  ([system id] (runs system id max-runs))
  ([system id limit]
   (vec (take limit (rseq (or (get @(:runs system) (norm-id id)) []))))))

(defn run-once
  "One pass of the agent loop: capture, render the prompt into the state,
  ask the decision engine, run :code. Records the pass in the run history
  and answers the ctx (with the :code result under :result). Throws when
  the decision or the code fails, after recording the error."
  [system agent]
  (let [id (:id agent)
        started (System/currentTimeMillis)
        run-id (str (name id) "-" started "-" (rand-int 100000))
        captures (capture system agent)
        state (sensor/render (:prompt agent) captures {:memory (:store system) :agent-id id})
        last-pass (when (:last-pass system) (get @(:last-pass system) id))
        cached? (boolean (and (:only-on-change agent) last-pass (= state (:state last-pass))))
        base {:id run-id
              :agent (id-str id)
              :time (str (java.time.Instant/ofEpochMilli started))
              :state state
              :captured (into {} (map (fn [[k v]] [(name k) (summarize-capture v)]) captures))
              :cached cached?}]
    (try
      (let [answers (if cached?
                      (:answers last-pass)
                      (let [result ((:decide system) state (decision-questions agent))]
                        (if (:trends system)
                          (with-noul-trend (:trends system) id (decision/answers result))
                          (decision/answers result))))
            _ (when (and (:last-pass system) (not cached?))
                (swap! (:last-pass system) assoc id {:state state :answers answers}))
            ctx (build-ctx system agent captures answers state)
            code (resolve-code (:code agent))
            result (when code (code ctx))]
        (record-run! system id (assoc base :decision answers :act (:act? ctx)
                                      :duration-ms (- (System/currentTimeMillis) started)))
        (assoc ctx :result result :run-id run-id))
      (catch Exception e
        (record-run! system id (assoc base :error (or (ex-message e) (str e))
                                      :duration-ms (- (System/currentTimeMillis) started)))
        (throw e)))))

;; --- the runtime ---------------------------------------------------------------------

(defn make-system
  "Assemble the running system from its parts. `engine` is a decision
  engine fn (see overseer.decision/make-engine); `sensors` and `tools` are
  registries (atoms, so later registrations are seen) or plain maps;
  `agents` the agent registry (the global one by default); `store` a
  memory store or nil (fresh); `config` the config map."
  [{:keys [engine sensors tools store config agents]}]
  {:store (or store (memory/make-store))
   :sensors (or sensors {})
   :tools (or tools {})
   :agents (or agents registry)
   :decide engine
   :config (or config {})
   :running (atom {})
   :threads (atom {})
   :runs (atom {})
   :trends (atom {})
   :last-pass (atom {})})

(defn- system-agent [system id] (get-agent (or (:agents system) registry) id))

(defn system-agents [system] (agents (or (:agents system) registry)))

(defn running?
  "Is the agent's loop thread live?"
  [system agent-id]
  (boolean (get @(:running system) (norm-id agent-id))))

(defn- unknown-agent [id]
  (ex-info (str "no agent " (id-str id)) {:type :unknown-agent :id id}))

(defn run-now!
  "Run one pass now; the agent must be registered. Answers the ctx."
  [system agent-id]
  (let [a (or (system-agent system agent-id) (throw (unknown-agent agent-id)))]
    (run-once system a)))

(defn- log-err [& xs]
  (binding [*out* *err*] (apply println "overseer:" xs) (flush)))

(defn start!
  "Start the agent's loop thread; no-op if already running. Each pass
  reads the agent's current definition, so an edit takes effect on the
  next pass; an unregistered agent stops. Answers agent-id."
  [system agent-id]
  (let [id (norm-id agent-id)]
    (when-not (system-agent system id) (throw (unknown-agent id)))
    (locking (:running system)
      (when-not (running? system id)
        (let [token (Object.)
              live? #(identical? token (get @(:running system) id))]
          (reset-trend! system id)
          (swap! (:last-pass system) dissoc id)
          (swap! (:running system) assoc id token)
          (let [thread (Thread.
                        (fn []
                          (loop []
                            (when (live?)
                              (let [a (system-agent system id)]
                                (if-not a
                                  (swap! (:running system) #(if (identical? token (get % id)) (dissoc % id) %))
                                  (do
                                    (try (run-once system a)
                                         (catch Exception e
                                           (when (live?)
                                             (log-err "agent" (id-str id) "failed:" (or (ex-message e) (str e))))))
                                    (when (live?)
                                      (try (Thread/sleep (long (or (:interval-ms a) 60000)))
                                           (catch Exception _ nil)))
                                    (recur))))))))]
            (.start thread)
            (swap! (:threads system) assoc id thread)))))
    id))

(defn stop!
  "Stop the agent's loop thread; no-op if not running. Answers agent-id.
  Safe to call from the agent's own code."
  [system agent-id]
  (let [id (norm-id agent-id)
        t (locking (:running system)
            (swap! (:running system) dissoc id)
            (reset-trend! system id)
            (let [t (get @(:threads system) id)]
              (swap! (:threads system) dissoc id)
              t))]
    ;; Interrupt only a sleeping loop from outside; an agent stopping
    ;; itself just lets its loop see the flag.
    (when (and t (not (identical? t (Thread/currentThread))))
      (.interrupt t))
    id))

(defn stop-all! [system]
  (doseq [id (keys @(:running system))] (stop! system id)))

(defn start-all!
  "Start every registered agent. Answers the ids."
  [system]
  (mapv #(start! system (:id %)) (system-agents system)))

(defn manager
  "The handle tools use to start/stop and read agents from a system."
  [system]
  {:start #(start! system %)
   :stop #(stop! system %)
   :agent #(system-agent system %)
   :agents #(system-agents system)})

(defn attach-manager
  "A system with its :manager handle set."
  [system]
  (assoc system :manager (manager system)))
