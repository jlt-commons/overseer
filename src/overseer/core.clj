(ns overseer.core
  "The overseer CLI.

    overseer list                    list agents, sensors and tools
    overseer run                     register everything, start all agents, run forever
    overseer serve [--port N]        register everything, start all agents, serve the
                                     HTTP API (--no-start: leave agents stopped)
    overseer run-once <agent-id>     run one pass of an agent, print the decision
    overseer capture <sensor-id>     capture one sensor, print the result
    overseer decide <text>           ask the engine one noul over the text

  Overseer registers the built-in sensors and tools, loads the plugins
  under ~/.config/overseer/plugins, loads any :agents from
  ~/.config/overseer/config.edn, then runs. The decision engine (Lev) is
  built lazily, so `list` and `capture` work without a prepared model."
  (:require [clojure.string :as str]
            [overseer.agent :as agent]
            [overseer.config :as cfg]
            [overseer.decision :as decision]
            [overseer.library :as library]
            [overseer.memory :as memory]
            [overseer.plugins :as plugins]
            [overseer.sensor :as sensor]
            [overseer.sensors]
            [overseer.server :as server]
            [overseer.tool :as tool]
            [overseer.tools]))

(def version "0.1.0")

;; --- setup ---------------------------------------------------------------------------

(defn- engine
  "A decision engine built lazily so no model loads until a decision is
  actually asked (Lev's namespaces are already loaded)."
  [config]
  (delay (decision/make-engine (decision/make-router config))))

(defn- register-config-agents!
  "Register the :agents from config.edn: {id agent-map}. The map's :code is
  a symbol or string, resolved when the agent first runs."
  [config]
  (doseq [[id a] (:agents config)]
    (try
      (agent/register-agent! (assoc (if (map? a) a {}) :id (keyword (name id))))
      (catch Exception e
        (binding [*out* *err*]
          (println "overseer: config agent" (name id) "-" (ex-message e)))))))

(defn setup
  "Register the built-ins, load plugins, register config agents, load the
  agents and sensors created over the API (<config-dir>/agents, /sensors),
  and assemble the running system. opts: :config (map), :plugins-dir,
  :store. Memory is kept in <config-dir>/memory.edn (config :memory-file;
  false keeps it in memory only). Answers the system."
  [{:keys [config plugins-dir store]}]
  (overseer.sensors/register!)
  (overseer.tools/register!)
  (let [config (or config (cfg/load-config))
        config (cond-> config
                 (not (contains? config :library-dir)) (assoc :library-dir (cfg/config-dir)))
        mem-file (if (contains? config :memory-file)
                   (:memory-file config)
                   (str (cfg/config-dir) "/memory.edn"))
        _ (plugins/load-plugins (or plugins-dir (cfg/plugins-dir nil config)))
        _ (register-config-agents! config)
        engine (engine config)
        system (agent/make-system {:engine (fn [& args] (apply @engine args))
                                   :sensors sensor/registry
                                   :tools tool/registry
                                   :agents agent/registry
                                   :store (or store (if mem-file
                                                      (memory/file-store mem-file)
                                                      (memory/make-store)))
                                   :config config})]
    (library/load-library! system)
    (agent/attach-manager system)))

;; --- list ----------------------------------------------------------------------------

(defn- name' [id] (subs (str id) 1))

(defn print-list [system]
  (println "agents:")
  (if (seq (agent/agents))
    (doseq [a (sort-by :id (agent/agents))]
      (println (format "  %-24s %s" (name' (:id a)) (or (:description a) (:name a)))))
    (println "  (none)"))
  (println "sensors:")
  (doseq [[id s] (sort-by key (sensor/all (:sensors system)))]
    (println (format "  %-24s %s" (name id) (or (:description s) (name (:kind s :text))))))
  (println "tools:")
  (doseq [[n t] (sort-by key (tool/all (:tools system)))]
    (println (format "  %-24s %s" (name n) (or (:doc t) "")))))

;; --- commands -------------------------------------------------------------------------

(defn- run-forever [system]
  (let [ids (agent/start-all! system)]
    (binding [*out* *err*]
      (println "overseer: running" (mapv name' ids)))
    @(promise)))

(defn- serve [system opts]
  (let [port (Long/parseLong (str (cfg/setting (cfg/context opts) "--port" "OVERSEER_PORT" :port "8080")))
        host (cfg/setting (cfg/context opts) "--host" "OVERSEER_HOST" :host "127.0.0.1")
        _server (server/start system {:port port :host host})
        started (if (get opts "--no-start") [] (agent/start-all! system))]
    (binding [*out* *err*]
      (println (format "overseer: listening on http://%s:%d" host port))
      (when (seq started) (println "overseer: running" (mapv name' started))))
    @(promise)))

(defn -main
  [& args]
  (let [opts (cfg/parse-args args)
        [cmd & rest] (:args opts)
        config (cfg/resolve-config opts)]
    (try
      (case cmd
        "list" (print-list (setup {:config config}))
        "run" (run-forever (setup {:config config}))
        "serve" (serve (setup {:config config}) opts)
        "run-once" (let [id (keyword (first rest))
                         system (setup {:config config})
                         ctx (agent/run-now! system id)]
                     (println (pr-str {:state (:prompt ctx) :decision (:decision ctx) :act? (:act? ctx)})))
        "capture" (let [system (setup {:config config})
                        id (keyword (first rest))
                        v (sensor/capture (:sensors system) id (agent/capture-ctx system nil))]
                    (println (pr-str v)))
        "decide" (let [system (setup {:config config})
                       engine (:decide system)
                       text (str/join " " rest)
                       result (engine text {"act" (decision/noul "Does the state call for action?")})]
                   (println (pr-str (get-in result ["answers" "act"]))))
        (do (println "overseer " version)
            (println "usage: overseer [list|run|serve [--no-start]|run-once <id>|capture <sensor>|decide <text>]")
            (System/exit 2)))
      (flush)
      (System/exit 0)
      (catch Exception e
        (binding [*out* *err*]
          (println "overseer:" (ex-message e)))
        (flush)
        (System/exit 1)))))
