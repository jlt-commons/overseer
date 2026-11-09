(ns overseer.tools
  "The built-in tools: memory, agent control, time, notifications, sound,
  a shell exec, and the messaging tools in overseer.notify (Discord,
  Telegram, Pushover, ntfy, webhook).

  Tools receive the running `system` (see overseer.tool) and any args; the
  memory tools read the store at (:store system) and default to the
  current agent id at (:agent-id system), mirroring the Observer tools'
  optional `agentId` argument."
  (:require [clojure.java.shell :refer [sh]]
            [overseer.memory :as mem]
            [overseer.notify :as notify]
            [overseer.tool :as tool]))

(def ^:private mac? (some? (re-find #"^Mac" (System/getProperty "os.name"))))

(defn- now [] (str (java.util.Date.)))

(defn- current-agent [system agent-id]
  (or agent-id (:agent-id system)))

(defn- agents-handle [system]
  (:manager system))

;; --- memory -----------------------------------------------------------------------

(defn get-memory [system & [agent-id]]
  (mem/memory (:store system) (current-agent system agent-id)))

(defn set-memory [system content & [agent-id]]
  (mem/set-memory! (:store system) (current-agent system agent-id) (str content))
  (get-memory system agent-id))

(defn append-memory [system content & [agent-id]]
  (mem/append-memory! (:store system) (current-agent system agent-id) (str content))
  (get-memory system agent-id))

(defn get-image-memory [system & [agent-id]]
  (mem/image-memory (:store system) (current-agent system agent-id)))

(defn set-image-memory [system images & [agent-id]]
  (mem/set-image-memory! (:store system) (current-agent system agent-id) (vec images))
  (get-image-memory system agent-id))

(defn append-image-memory [system images & [agent-id]]
  (mem/append-image-memory! (:store system) (current-agent system agent-id) (vec images))
  (get-image-memory system agent-id))

;; --- agents ------------------------------------------------------------------------

(defn start-agent [system & [agent-id]]
  (let [h (agents-handle system)]
    ((:start h) (keyword (name (current-agent system agent-id))))))

(defn stop-agent [system & [agent-id]]
  (let [h (agents-handle system)]
    ((:stop h) (keyword (name (current-agent system agent-id))))))

;; --- time / sleep -------------------------------------------------------------------

(defn time* [] (now))

(defn sleep* [ms] (Thread/sleep (long ms)) nil)

;; --- notifications -------------------------------------------------------------------

(defn notify* [_system title body]
  (let [t (str title) b (str body)]
    (if mac?
      (sh "osascript" "-e" (str "display notification " (pr-str b) " with title " (pr-str t)))
      (sh "notify-send" t b))
    nil))

(defn sound* [_system & [name]]
  (if mac?
    (sh "afplay" (or name "/System/Library/Sounds/Glass.aiff"))
    (sh "paplay" (or name "/usr/share/sounds/freedesktop/stereo/bell.oga")))
  nil)

;; --- exec ---------------------------------------------------------------------------

(defn exec* [_system cmd & args]
  (let [r (apply sh (str cmd) (map str args))]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

;; --- the built-ins -------------------------------------------------------------------

(defn builtins
  "The built-in tools as a {name tool} map."
  []
  {:get-memory        {:name "get-memory" :doc "Read an agent's stored text (optional agentId)" :fn get-memory}
   :set-memory        {:name "set-memory" :doc "Replace an agent's stored text" :fn set-memory}
   :append-memory     {:name "append-memory" :doc "Append to an agent's stored text" :fn append-memory}
   :get-image-memory  {:name "get-image-memory" :doc "Read an agent's stored images" :fn get-image-memory}
   :set-image-memory  {:name "set-image-memory" :doc "Replace an agent's stored images" :fn set-image-memory}
   :append-image-memory {:name "append-image-memory" :doc "Append to an agent's stored images" :fn append-image-memory}
   :start-agent       {:name "start-agent" :doc "Start a registered agent (optional agentId)" :fn start-agent}
   :stop-agent        {:name "stop-agent" :doc "Stop a registered agent (optional agentId)" :fn stop-agent}
   :time              {:name "time" :doc "The current time" :fn (fn [_] (time*))}
   :sleep             {:name "sleep" :doc "Sleep for a number of milliseconds" :fn (fn [_ ms] (sleep* ms))}
   :notify            {:name "notify" :doc "A desktop notification" :fn notify*}
   :sound             {:name "sound" :doc "Play a local sound" :fn sound*}
   :exec              {:name "exec" :doc "Run a shell command" :fn exec*}})

(defn all-builtins
  "The built-ins plus the messaging tools (overseer.notify)."
  []
  (merge (builtins) (notify/builtins)))

(defn register!
  "Install the built-ins into a registry (the global one by default)."
  ([] (register! tool/registry))
  ([reg] (doseq [[_ t] (all-builtins)] (tool/register! reg t)) reg))
