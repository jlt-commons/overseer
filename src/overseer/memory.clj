(ns overseer.memory
  "Per-agent stored text (`$MEMORY`) and images (`$IMEMORY`). A store is
  any value the functions here can deref and swap! — an atom by default,
  so a plugin can substitute a persistent implementation.

  Backs the `$MEMORY` / `$MEMORY@agent_id` and `$IMEMORY` /
  `$IMEMORY@agent_id` sensors, and the get/set/append-memory tools.

  `file-store` is an atom that loads from and saves to an EDN file, so
  memory survives a restart."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn make-store
  "A fresh in-memory store: {agent-id {:memory \"\" :imemory [...]}}."
  []
  (atom {}))

(defn file-store
  "A store kept in the EDN file at `path`: loaded now (an unreadable file
  starts empty, and is left in place), written after every change."
  [path]
  (let [f (io/file path)
        init (try (when (.exists f)
                    (let [m (edn/read-string (slurp f))] (when (map? m) m)))
                  (catch Exception e
                    (binding [*out* *err*]
                      (println "overseer: cannot read memory" (str f) "-" (ex-message e)))
                    nil))
        store (atom (or init {}))
        lock (Object.)]
    (add-watch store ::persist
               (fn [_ _ old new]
                 (when-not (= old new)
                   (locking lock
                     (try (some-> (.getParentFile f) .mkdirs)
                          (let [tmp (io/file (str path ".tmp"))]
                            (spit tmp (pr-str @store))
                            (.renameTo tmp f))
                          (catch Exception e
                            (binding [*out* *err*]
                              (println "overseer: cannot save memory" (str f) "-" (ex-message e)))))))))
    store))

(defn- k [agent-id] (if (keyword? agent-id) agent-id (keyword (str agent-id))))

(defn- entry [store agent-id]
  (or (get @store (k agent-id)) {:memory "" :imemory []}))

(defn memory
  "The text memory for `agent-id` (defaults to the current agent's id)."
  [store agent-id]
  (:memory (entry store agent-id)))

(defn image-memory
  "The image memory (a vector of images) for `agent-id`."
  [store agent-id]
  (:imemory (entry store agent-id)))

(defn set-memory!
  "Replace the text memory for `agent-id` with `s`; returns the store."
  [store agent-id s]
  (swap! store assoc-in [(k agent-id) :memory] (str s))
  store)

(defn append-memory!
  "Append `s` to the text memory for `agent-id`; returns the store."
  [store agent-id s]
  (swap! store update-in [(k agent-id) :memory] (fnil str "") (str s))
  store)

(defn set-image-memory!
  "Replace the image memory for `agent-id` with `imgs` (a vector); returns the store."
  [store agent-id imgs]
  (swap! store assoc-in [(k agent-id) :imemory] (vec imgs))
  store)

(defn append-image-memory!
  "Append `imgs` to the image memory for `agent-id`; returns the store."
  [store agent-id imgs]
  (swap! store update-in [(k agent-id) :imemory] (fnil into []) imgs)
  store)

(defn delete!
  "Forget the text and image memory of `agent-id`; returns the store."
  [store agent-id]
  (swap! store dissoc (k agent-id))
  store)

(defn agents
  "The agent ids that have any stored memory."
  [store]
  (mapv (comp #(subs % 1) str) (keys @store)))
