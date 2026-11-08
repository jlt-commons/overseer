(ns overseer.plugins
  "Load plugin namespaces at startup.

  Plugins live under ~/.config/overseer/plugins/ (or OVERSEER_PLUGINS_DIR
  / config.edn :plugins-dir), one directory per plugin. Every `.clj` file
  under that tree is `load-file`d, so a plugin is one or more Jolt
  namespaces that register sensors, tools and agents on load (see
  overseer.plugin).

  Load order is by path, so a plugin can rely on another loaded earlier;
  the last registration of a given id or name wins."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn clj-files
  "Every *.clj under `dir`, sorted by path; nil when `dir` is missing or
  not a directory."
  [dir]
  (let [d (io/file dir)]
    (when (and (.exists d) (.isDirectory d))
      (->> (file-seq d)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))
           (sort-by #(.getPath %))))))

(defn load-plugin
  "load-file one plugin file; answers its path. Throws ex-info {:file ...}
  when the file does not load."
  [file]
  (let [path (.getPath (io/file file))]
    (try (binding [*ns* *ns*] (load-file path))
         (catch Exception e
           (throw (ex-info (str "plugin " path ": failed to load: " (ex-message e))
                           {:file path} e))))
    path))

(defn load-plugins
  "Load every plugin file under `dir`; answers the paths loaded ([] when
  the directory is missing)."
  [dir]
  (mapv load-plugin (clj-files dir)))
