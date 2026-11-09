(ns overseer.library
  "Agents and sensors created at runtime (over the HTTP API), and where
  they are kept so they survive a restart:

    <config-dir>/agents/<id>.edn     one agent; its :code is Jolt source
    <config-dir>/sensors/<id>.edn    one command sensor (overseer.sensors/command-sensor)

  Also the JSON shapes the API reads and writes. An agent in JSON:

    {\"id\": \"build_watch\", \"name\": \"..\", \"description\": \"..\",
     \"prompt\": \"Build log:\\n$BUILD_LOG\\n$MEMORY\",
     \"sensors\": [\"build-log\"],              // optional: the prompt's placeholders are captured anyway
     \"decision\": {\"type\": \"noul\", \"instructions\": \"Did the build fail?\", \"threshold\": 0.8},
     \"interval_ms\": 30000,                    // or loop_interval_seconds
     \"only_on_change\": false,
     \"code\": \"(fn [ctx] (when (:act? ctx) ((:call ctx) \\\"notify\\\" \\\"Build\\\" \\\"failed\\\")))\"}"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [overseer.agent :as agent]
            [overseer.sensor :as sensor]
            [overseer.sensors :as sensors]))

;; --- JSON <-> agent ------------------------------------------------------------------

(defn- invalid [msg] (ex-info msg {:type :invalid-request}))

(defn- json-key [m & ks] (some #(get m %) ks))

(defn json->decision
  "A decision map from its JSON form: the gate keys become keywords,
  \"questions\" and \"criteria\" keep their string keys (option order is
  model input)."
  [d]
  (cond
    (nil? d) nil
    (not (map? d)) (throw (invalid "decision must be an object"))
    :else (into {} (map (fn [[k v]]
                          (let [k (keyword (str/replace (name k) "_" "-"))]
                            [k (if (= :type k) (keyword (name v)) v)])))
                d)))

(defn json->agent
  "An agent definition from the API's JSON body (string keys). `id`
  overrides the body's \"id\"."
  ([m] (json->agent m nil))
  ([m id]
   (when-not (map? m) (throw (invalid "the agent must be a JSON object")))
   (let [id (or id (json-key m "id"))
         prompt (json-key m "prompt" "system_prompt")
         secs (json-key m "loop_interval_seconds")
         ms (json-key m "interval_ms" "interval-ms")
         sensors (json-key m "sensors")
         code (json-key m "code")]
     (when-not (and (string? id) (seq id)) (throw (invalid "the agent needs an \"id\"")))
     (when-not (string? prompt) (throw (invalid "the agent needs a \"prompt\" string")))
     (when (and (some? code) (not (string? code)))
       (throw (invalid "\"code\" must be a string of Jolt source, e.g. \"(fn [ctx] ...)\"")))
     (when (and (some? sensors) (not (and (sequential? sensors) (every? string? sensors))))
       (throw (invalid "\"sensors\" must be a list of sensor ids")))
     (when (and (some? ms) (not (number? ms))) (throw (invalid "\"interval_ms\" must be a number")))
     (when (and (some? secs) (not (number? secs))) (throw (invalid "\"loop_interval_seconds\" must be a number")))
     (cond-> {:id (keyword id)
              :prompt prompt
              :interval-ms (long (cond ms ms secs (* 1000 secs) :else 30000))}
       (json-key m "name") (assoc :name (str (json-key m "name")))
       (json-key m "description") (assoc :description (str (json-key m "description")))
       (seq sensors) (assoc :sensors (mapv keyword sensors))
       (json-key m "decision") (assoc :decision (json->decision (json-key m "decision")))
       (json-key m "only_on_change" "only_on_significant_change") (assoc :only-on-change true)
       (and code (not (str/blank? code))) (assoc :code code)))))

(defn- jsonable
  "Keywords (keys and values) as plain strings, for the JSON response."
  [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (str x)
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (str/replace (name k) "-" "_") (str k)) (jsonable v)])) x)
    (sequential? x) (mapv jsonable x)
    (fn? x) "<fn>"
    :else x))

(defn agent->json
  "The API's view of an agent definition."
  [system a]
  (let [code (:code a)]
    {:id (agent/id-str (:id a))
     :name (:name a)
     :description (:description a)
     :prompt (:prompt a)
     :sensors (mapv name (or (:sensors a) []))
     :captures (mapv name (agent/prompt-sensors (:sensors system) a))
     :decision (jsonable (agent/decision-questions a))
     :gate (jsonable (select-keys (:decision a) [:threshold :act :min]))
     :interval_ms (:interval-ms a)
     :only_on_change (boolean (:only-on-change a))
     :code (cond (string? code) code (symbol? code) (str code) (fn? code) "<fn>" :else nil)
     :editable (not (fn? code))
     :running (agent/running? system (:id a))}))

;; --- validation -----------------------------------------------------------------------

(defn check-agent!
  "Validate a definition fully before it is registered: the agent shape,
  its decision, its code (evaluated now, so a syntax error fails the
  request rather than every pass), and that its :sensors exist."
  [system a]
  (try (agent/validate! a)
       (agent/decision-questions a)
       (agent/resolve-code (:code a))
       (catch Exception e
         (throw (ex-info (ex-message e) {:type :invalid-request}))))
  (let [known (sensor/all (:sensors system))
        missing (remove #(contains? known %) (:sensors a))]
    (when (seq missing)
      (throw (invalid (str "unknown sensors: " (str/join ", " (map name missing))
                           "; see GET /sensors")))))
  a)

;; --- persistence ----------------------------------------------------------------------

(defn- dir [system kind]
  (when-let [d (get-in system [:config :library-dir])]
    (io/file d kind)))

(defn- file-of [system kind id]
  (when-let [d (dir system kind)] (io/file d (str (name id) ".edn"))))

(defn- save! [system kind id m]
  (when-let [f (file-of system kind id)]
    (.mkdirs (.getParentFile f))
    (spit f (with-out-str (prn m)))))

(defn- forget! [system kind id]
  (when-let [f (file-of system kind id)]
    (when (.exists f) (.delete f))))

(defn- read-all [d]
  (when (and d (.isDirectory d))
    (->> (.listFiles d)
         (filter #(str/ends-with? (.getName %) ".edn"))
         (sort-by #(.getName %))
         (keep (fn [f]
                 (try (edn/read-string (slurp f))
                      (catch Exception e
                        (binding [*out* *err*]
                          (println "overseer: skipping" (str f) "-" (ex-message e)))
                        nil)))))))

(defn persistable?
  "Can this agent be written to disk? (Its code is source or a symbol.)"
  [a]
  (not (fn? (:code a))))

(defn save-agent!
  "Register `a` and write it to the library."
  [system a]
  (let [a (agent/register-agent! (:agents system) a)]
    (when (persistable? a)
      (save! system "agents" (:id a) (cond-> a (symbol? (:code a)) (update :code str))))
    a))

(defn delete-agent!
  "Stop, unregister and forget agent `id` (and its stored file)."
  [system id]
  (agent/stop! system id)
  (agent/unregister-agent! (:agents system) id)
  (forget! system "agents" id))

(defn save-sensor!
  "Build a command sensor from `spec`, register it and write it to the
  library."
  [system spec]
  (let [s (sensors/command-sensor spec)]
    (sensor/register! (:sensors system) s)
    (save! system "sensors" (:id s) (select-keys s [:id :kind :cmd :description :mime]))
    s))

(defn delete-sensor! [system id]
  (swap! (:sensors system) dissoc (keyword id))
  (forget! system "sensors" id))

(defn json->sensor
  [m]
  (when-not (map? m) (throw (invalid "the sensor must be a JSON object")))
  (let [id (get m "id") cmd (get m "cmd")]
    (when-not (and (string? id) (re-matches (re-pattern "[a-z][a-z0-9-]*") id))
      (throw (invalid "the sensor needs an \"id\" of lowercase letters, digits and dashes (its placeholder is $ID with _ for -)")))
    (when-not (or (string? cmd) (and (sequential? cmd) (seq cmd) (every? string? cmd)))
      (throw (invalid "the sensor needs \"cmd\": a list of strings, e.g. [\"df\", \"-h\", \"/\"]")))
    (when-not (contains? #{nil "text" "image"} (get m "kind"))
      (throw (invalid "\"kind\" is \"text\" or \"image\"")))
    (cond-> {:id (keyword id) :kind (keyword (or (get m "kind") "text")) :cmd cmd}
      (get m "description") (assoc :description (get m "description"))
      (get m "mime") (assoc :mime (get m "mime")))))

(defn load-library!
  "Register the sensors, then the agents, kept in the library dir. An
  entry that fails to load is reported and skipped."
  [system]
  (doseq [spec (read-all (dir system "sensors"))]
    (try (sensor/register! (:sensors system) (sensors/command-sensor spec))
         (catch Exception e
           (binding [*out* *err*] (println "overseer: sensor" (:id spec) "-" (ex-message e))))))
  (doseq [a (read-all (dir system "agents"))]
    (try (agent/register-agent! (:agents system) a)
         (catch Exception e
           (binding [*out* *err*] (println "overseer: agent" (:id a) "-" (ex-message e)))))))
