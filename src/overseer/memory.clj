(ns overseer.memory
  "Per-agent stored text (`$MEMORY`) and images (`$IMEMORY`). A store is
  any value the functions here can deref and swap! — an atom by default,
  so a plugin can substitute a persistent implementation.

  Backs the `$MEMORY` / `$MEMORY@agent_id` and `$IMEMORY` /
  `$IMEMORY@agent_id` sensors, and the get/set/append-memory tools.")

(defn make-store
  "A fresh in-memory store: {agent-id {:memory \"\" :imemory [...]}}."
  []
  (atom {}))

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

(defn agents
  "The agent ids that have any stored memory."
  [store]
  (mapv (comp #(subs % 1) str) (keys @store)))
