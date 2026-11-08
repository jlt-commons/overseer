(ns overseer.core
  "The overseer CLI.

    overseer list                    list agents, sensors and tools
    overseer run                     register everything, start all agents, run forever
    overseer serve [--port N]        register everything, serve the HTTP API
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
    (agent/register-agent!
     (-> (if (map? a) a {})
         (assoc :id (keyword (name id)))
         (assoc :code (or (:code a) a))))))

(defn setup
  "Register the built-ins, load plugins, register config agents, and
  assemble the running system. opts: :config (map), :plugins-dir, :store.
  Answers the system."
  [{:keys [config plugins-dir store]}]
  (overseer.sensors/register!)
  (overseer.tools/register!)
  (let [config (or config (cfg/load-config))
        _ (plugins/load-plugins (or plugins-dir (cfg/plugins-dir nil config)))
        _ (register-config-agents! config)
        system (agent/make-system {:engine (fn [& args] (apply @(engine config) args))
                                   :sensors (sensor/all)
                                   :tools (tool/all)
                                   :store (or store (memory/make-store))
                                   :config config})]
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
  (doseq [[id s] (sort-by key (:sensors system))]
    (println (format "  %-24s %s" (name id) (or (:description s) (name (:kind s :text))))))
  (println "tools:")
  (doseq [[n t] (sort-by key (:tools system))]
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
        _ (agent/start-all! system)
        _server (server/start system {:port port :host host})]
    (binding [*out* *err*]
      (println (format "overseer: listening on http://%s:%d" host port)))
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
                     (println (pr-str {:decision (:decision ctx) :act? (:act? ctx)})))
        "capture" (let [system (setup {:config config})
                        id (keyword (first rest))
                        v (sensor/capture (:sensors system) id
                                          {:store (:store system) :memory (:store system)
                                           :agent-id nil
                                           :transcribe (get-in (:config system) [:audio :transcribe])})]
                    (println (pr-str v)))
        "decide" (let [system (setup {:config config})
                       engine (:decide system)
                       text (str/join " " rest)
                       result (engine text {"act" (decision/noul "Does the state call for action?")})]
                   (println (pr-str (get-in result ["answers" "act"]))))
        (do (println "overseer " version)
            (println "usage: overseer [list|run|serve|run-once <id>|capture <sensor>|decide <text>]")
            (System/exit 2)))
      (flush)
      (System/exit 0)
      (catch Exception e
        (binding [*out* *err*]
          (println "overseer:" (ex-message e)))
        (flush)
        (System/exit 1)))))
