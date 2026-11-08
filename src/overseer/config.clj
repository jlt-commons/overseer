(ns overseer.config
  "~/.config/overseer: `config.edn` (where the prepared Lev model lives,
  server defaults, sensor command overrides) and the one precedence rule
  every entry point follows: CLI flag > environment variable > config.edn >
  default.

  config.edn keys:
    :data            prepared Lev data root (jolt prepare --out)
    :thinkers        {\"name\" {:model \"x.gguf\" ...}} generative models (lev.think)
    :encoders        {\"name\" dir} prepared encoder directories, overriding :data
    :models          {\"name\" dir} like :encoders
    :max-loaded :max-thinkers :default-model
                     router settings (lev.router)
    :port :host :api-key
                     server defaults
    :plugins-dir     where plugin namespaces are loaded from (default
                     ~/.config/overseer/plugins)
    :agents          {\"id\" agent-map} EDN-defined agents (agent :code is a
                     fully-qualified symbol, resolved on load)
    :screen :camera :clipboard :audio
                     per-sensor command overrides, e.g. {:screen {:cmd \"screencapture\" :args [\"-x\" :file]}}

  Environment (each also settable in config.edn):
    OVERSEER_CONFIG_DIR  instead of $XDG_CONFIG_HOME/overseer or ~/.config/overseer
    OVERSEER_DATA        :data
    OVERSEER_PORT        :port
    OVERSEER_HOST        :host
    OVERSEER_API_KEY     :api-key
    OVERSEER_PLUGINS_DIR :plugins-dir

  The pure functions take an `env` map so tests never read the real
  environment; nil means the process environment."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse-args
  "--flag value pairs, bare --flags (true) and positionals under :args:
  (parse-args [\"serve\" \"--port\" \"80\" \"--self-test\"])
  => {:args [\"serve\"] \"--port\" \"80\" \"--self-test\" true}"
  [args]
  (loop [args (seq args) out {:args []}]
    (if (empty? args)
      out
      (let [[k v & more] args]
        (cond
          (not (str/starts-with? k "--")) (recur (rest args) (update out :args conj k))
          (and v (not (str/starts-with? v "--"))) (recur more (assoc out k v))
          :else (recur (rest args) (assoc out k true)))))))

(defn- getenv [env k]
  (if (nil? env) (System/getenv k) (get env k)))

(defn config-dir
  "OVERSEER_CONFIG_DIR, else $XDG_CONFIG_HOME/overseer, else ~/.config/overseer."
  ([] (config-dir nil))
  ([env]
   (or (getenv env "OVERSEER_CONFIG_DIR")
       (some-> (getenv env "XDG_CONFIG_HOME") (str "/overseer"))
       (str (or (getenv env "HOME") (System/getProperty "user.home")) "/.config/overseer"))))

(defn load-config
  "The map in <dir>/config.edn, or {} when there is no such file."
  ([] (load-config (config-dir)))
  ([dir]
   (let [f (io/file dir "config.edn")]
     (if-not (.exists f)
       {}
       (let [m (try (edn/read-string (slurp f))
                    (catch Exception e
                      (throw (ex-info (str "cannot read " f ": " (ex-message e)) {:file (str f)} e))))]
         (when-not (map? m)
           (throw (ex-info (str f " must hold a map, not " (pr-str m)) {:file (str f)})))
         m)))))

(defn plugins-dir
  "The directory plugin namespaces load from: OVERSEER_PLUGINS_DIR /
  config.edn :plugins-dir, else <config-dir>/plugins."
  ([env config] (or (getenv env "OVERSEER_PLUGINS_DIR") (:plugins-dir config) (str (config-dir env) "/plugins")))
  ([env] (plugins-dir env (load-config (config-dir env)))))

(defn context
  "What the resolvers below need: parsed CLI opts ({\"--data\" \"dir\"}), the
  process environment, and config.edn."
  ([] (context {}))
  ([opts]
   {:opts opts :env nil :config (load-config (config-dir))}))

(defn setting
  "CLI flag > env var > config.edn key > default. A bare --flag (true) is
  not a value."
  [{:keys [opts env config]} flag env-var key default]
  (let [v (get opts flag)
        e (getenv env env-var)]
    (cond
      (string? v) v
      (some? e) e
      (contains? config key) (get config key)
      :else default)))

(defn resolve-config
  "The config.edn map with the settings the CLI and environment can
  override resolved: --data / OVERSEER_DATA, --plugins / OVERSEER_PLUGINS_DIR,
  --port / OVERSEER_PORT, --host / OVERSEER_HOST, --api-key / OVERSEER_API_KEY."
  [opts]
  (let [ctx (context opts)
        config (:config ctx)
        set' (fn [flag env key default] (setting ctx flag env key default))]
    (assoc config
           :data (set' "--data" "OVERSEER_DATA" :data "data")
           :plugins-dir (set' "--plugins" "OVERSEER_PLUGINS_DIR" :plugins-dir nil)
           :port (set' "--port" "OVERSEER_PORT" :port "8080")
           :host (set' "--host" "OVERSEER_HOST" :host "127.0.0.1")
           :api-key (set' "--api-key" "OVERSEER_API_KEY" :api-key nil))))
