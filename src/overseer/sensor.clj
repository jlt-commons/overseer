(ns overseer.sensor
  "The sensor registry and the `$SENSOR` placeholder language.

  A sensor is a map:

    {:id :screen                ; a keyword; its name becomes the placeholder
     :description \"...\"        ; shown by `overseer list`
     :kind :image               ; :image or :text (affects inlining)
     :capture (fn [ctx] value)} ; ctx is the capture context (memory store,
                                ; agent id, etc.), value below

  `capture` answers one of:

    - a string (text), e.g. the clipboard contents
    - {:type :image :mime \"image/png\" :bytes b} (an image)
    - nil (the sensor is unavailable right now)

  Placeholders: `$SCREEN`, `$CAMERA`, `$CLIPBOARD`, `$MICROPHONE`,
  `$SCREEN_AUDIO`, `$ALL_AUDIO`, and `$MEMORY` / `$MEMORY@agent_id` /
  `$IMEMORY` / `$IMEMORY@agent_id` for another agent's stored memory. A
  placeholder is a sensor id uppercased with dashes turned to underscores."
  (:require [clojure.string :as str]
            [overseer.memory :as mem]
            [overseer.placeholder :as ph]))

;; The process-wide sensor registry. Plugins and built-ins register here;
;; the runtime works from a plain map so tests never touch it.
(defonce registry (atom {}))

(defn placeholder
  "Sensor id -> placeholder token: :screen -> \"$SCREEN\", :all-audio ->
  \"$ALL_AUDIO\"."
  [id]
  (ph/placeholder id))

(defn register!
  "Add a sensor to a registry (the global one by default). Throws when the
  map lacks an :id or a :capture fn."
  ([sensor] (register! registry sensor))
  ([reg sensor]
   (let [id (:id sensor)]
     (when-not (keyword? id)
       (throw (ex-info "a sensor needs a keyword :id" {:sensor sensor})))
     (when-not (fn? (:capture sensor))
       (throw (ex-info (str "sensor " id " needs a :capture fn") {:sensor sensor})))
     (swap! reg assoc id sensor)
     sensor)))

(defn unregister! [id] (swap! registry dissoc id))

(defn all ([] @registry) ([reg] (if (map? reg) reg @reg)))

(defn get-sensor
  ([id] (get @registry id))
  ([reg id] (get (if (map? reg) reg @reg) id)))

(defn capture
  "Capture one sensor by id. Unknown sensor -> nil. A failing capture is
  caught and returned as {:type :error :message ...} (the agent loop must
  not die on a bad sensor); pass :throw? true to let it propagate."
  ([id ctx] (capture registry id ctx))
  ([reg id ctx] (capture reg id ctx false))
  ([reg id ctx throw?]
   (let [s (get-sensor reg id)]
     (when s
       (try ((:capture s) ctx)
            (catch Exception e
              (if throw?
                (throw (ex-info (str "sensor " id " failed: " (ex-message e)) {:sensor id} e))
                {:type :error :message (ex-message e)})))))))

(defn capture-many
  "Capture several sensor ids against one ctx; answers {id -> value}."
  ([reg ids ctx] (capture-many reg ids ctx false))
  ([reg ids ctx throw?]
   (into {} (map (fn [id] [id (capture reg id ctx throw?)]) ids))))

;; --- placeholder rendering ------------------------------------------------------

(defn render-value
  "A captured value inlined into text for the model's state. Text passes
  through; an image becomes a marker; nil/errors become \"\"."
  [v]
  (cond
    (nil? v) ""
    (string? v) v
    (and (map? v) (= :image (:type v))) (str "[image:" (:mime v "image") "]")
    (and (map? v) (= :error (:type v))) ""
    (map? v) (pr-str v)
    (sequential? v) (str/join "\n" (map render-value v))
    :else (str v)))

(def placeholder-re
  "A placeholder: $NAME with an optional @agent_id suffix."
  (re-pattern "\\$([A-Z][A-Z0-9_]*)(@[A-Za-z0-9_-]+)?"))

(defn sensor-id-of [placeholder]
  (ph/sensor-id-of placeholder))

(defn render
  "Substitute `$SENSOR` placeholders in `text` using the captured `values`
  ({sensor-id -> value}). Memory placeholders carry an @agent_id suffix;
  `memory` is the store (see overseer.memory) and `agent-id` the current
  agent, the default for a bare `$MEMORY` / `$IMEMORY`. A placeholder
  that names no captured sensor (e.g. `$HOME` in a shell snippet) is left
  untouched."
  [text values {:keys [memory agent-id]}]
  (let [memory (or memory {})]
    (str/replace text placeholder-re
                 (fn [[match placeholder at]]
                   (let [id (sensor-id-of placeholder)
                         at-id (when at (subs at 1))]
                     (cond
                       (and at-id (= :memory id))
                       (render-value (mem/memory memory at-id))

                       (and at-id (= :imemory id))
                       (render-value (mem/image-memory memory at-id))

                       (= :memory id)
                       (render-value (mem/memory memory agent-id))

                       (= :imemory id)
                       (render-value (mem/image-memory memory agent-id))

                       (contains? values id)
                       (render-value (get values id))

                       ;; not a sensor this pass captured: leave the text as is
                       :else match))))))
