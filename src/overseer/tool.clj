(ns overseer.tool
  "The tool registry. A tool is a map:

    {:name \"append-memory\"   ; the name agent code and the HTTP API use
     :doc  \"...\"             ; shown by `overseer list`
     :fn   (fn [system & args] ...)}

  `system` is the running overseer: {:memory store, :agents agent-manager,
  :sensors sensor-map, :tools tool-map, :decide engine-fn}. Plugins add
  tools with `register!`; agent code calls them with `call`.")

;; The process-wide tool registry (plugins and built-ins register here).
(defonce registry (atom {}))

(defn register!
  "Add a tool to a registry (the global one by default). Throws when the
  map lacks a :name (string/keyword) or a :fn."
  ([tool] (register! registry tool))
  ([reg tool]
   (let [name (:name tool)]
     (when-not (or (string? name) (keyword? name))
       (throw (ex-info "a tool needs a :name (string or keyword)" {:tool tool})))
     (when-not (fn? (:fn tool))
       (throw (ex-info (str "tool " name " needs a :fn") {:tool tool})))
     (swap! reg assoc (keyword name) tool)
     tool)))

(defn unregister! [name] (swap! registry dissoc (keyword name)))

(defn all ([] @registry) ([reg] (if (map? reg) reg @reg)))

(defn get-tool
  ([name] (get @registry (keyword name)))
  ([reg name] (get (if (map? reg) reg @reg) (keyword name))))

(defn call
  "Invoke a tool by name with `args`, threading `system` first. Unknown
  tool -> throws ex-info {:type :unknown-tool}."
  [reg system name & args]
  (let [t (get-tool reg name)]
    (when-not t
      (throw (ex-info (str "no tool named " (pr-str name))
                      {:type :unknown-tool :name name
                       :known (vec (sort (map (comp #(subs % 1) str) (keys (if (map? reg) reg @reg)))))})))
    (apply (:fn t) system args)))
