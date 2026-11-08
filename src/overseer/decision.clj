(ns overseer.decision
  "The Lev decision engine behind the agent loop.

  Lev is the decision engine: it answers typed questions (noul / choice /
  score) over a text state, either on a small encoder checkpoint or a
  generative thinker. This namespace wraps `lev.router` as a single
  `engine` fn an agent can call, and re-exports the question constructors
  as plain helpers.

  `make-router` and `make-engine` are the only places that touch Lev. Lev is
  required statically (so `jolt build` bakes it into the binary), but no
  model loads until the engine's first call, so `list` and `capture` stay
  cheap. An engine fails on first use with a clear message, not at startup,
  when no data dir or thinker is available."
  (:require [lev.agent :as ag]
            [lev.router :as router]))

;; --- question constructors (ordered maps: option order is model input) ---------------

(defn noul
  "A yes/no question; optional {\"true\" .. \"false\"} criteria."
  ([instructions] (array-map "type" "noul" "instructions" instructions))
  ([instructions criteria]
   (array-map "type" "noul" "instructions" instructions "criteria" criteria)))

(defn choice
  "A choice question: criteria as {option description} (an ordered map) or
  a list of options."
  [instructions criteria]
  (array-map "type" "choice" "instructions" instructions
             "criteria" (if (map? criteria)
                          criteria
                          (into (array-map) (map (fn [c] [c nil]) criteria)))))

(defn score
  "An ordinal question over `criteria` (2-10 strings)."
  [instructions criteria]
  (array-map "type" "score" "instructions" instructions "criteria" (vec criteria)))

;; --- answer extraction --------------------------------------------------------------

(defn answers
  "The `answers` map out of a predict result."
  [result]
  (get result "answers" {}))

(defn noul-of
  "The yes-probability of a noul answer (a number)."
  [answer]
  (double (get answer "noul" 0.0)))

(defn choice-of
  "The chosen option of a choice answer."
  [answer]
  (get answer "choice"))

(defn score-of
  "The numeric score of a score answer."
  [answer]
  (double (get answer "score" 0.0)))

(defn confidence-of
  "The confidence of any typed answer."
  [answer]
  (double (get answer "confidence" 0.0)))

(defn default-question
  "The single question an agent's `:decision` map stands for when it
  carries no explicit question ids: a noul/choice/score built from
  :type/:instructions/:criteria, asked under the id \"act\"."
  [{:keys [type instructions criteria]}]
  (case (keyword type)
    :noul (noul (or instructions "Does the state call for action?"))
    :choice (choice (or instructions "Which action fits the state?") (or criteria {}))
    :score (score (or instructions "How strongly does the state call for action?") (or criteria []))
    (throw (ex-info (str "unknown decision type " (pr-str type) "; one of noul, choice, score")
                    {:type :invalid-decision :decision-type type}))))

;; --- the Lev engine (loaded lazily) ---------------------------------------------------

(defn make-router
  "A Lev router for overseer's config: :data (the prepared data root),
  :thinkers, :encoders/:models, :max-loaded, :max-thinkers,
  :default-model."
  [config]
  (router/make-router
   (cond-> {:data (or (:data config) "data")
            :models (merge (or (:encoders config) {}) (or (:models config) {}))
            :max-loaded (long (or (:max-loaded config) 1))
            :default (or (:default-model config) "english")}
     (:thinkers config) (assoc :thinkers (:thinkers config))
     (:max-thinkers config) (assoc :max-thinkers (long (:max-thinkers config))))))

(defn make-engine
  "A decision engine from a Lev router (or a loaded agent): (fn [state
  questions] answer) and (fn [state questions opts] answer), where `opts`
  are the keyword args `lev.router/predict` takes (:model :constraints
  :thinking ...)."
  [rt]
  (let [predict (if (router/router? rt)
                  (fn [state questions opts] (apply router/predict rt state questions (mapcat identity opts)))
                  (fn [state questions opts] (apply ag/system-one rt state questions (mapcat identity opts))))]
    (fn engine
      ([state questions] (predict state questions {}))
      ([state questions opts] (predict state questions opts)))))

(defn engine? [e] (fn? e))
