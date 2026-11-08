(ns overseer.placeholder
  "The `$SENSOR` placeholder language, as pure single-arity functions so a
  writ spec can pin them down: a sensor id (keyword) maps to its
  `$UPPERCASE` token and back.

    (placeholder :screen)        ;; \"$SCREEN\"
    (sensor-id-of \"$SCREEN\")     ;; :screen

  The round trip holds for every id with no underscores: the placeholder
  renders dashes as underscores (the token alphabet has no dash)."
  (:require [clojure.string :as str]))

(defn placeholder
  "Sensor id -> placeholder token."
  [id]
  (str "$" (str/upper-case (str/replace (name id) "-" "_"))))

(defn sensor-id-of
  "Placeholder token -> sensor id. The leading `$` is optional."
  [s]
  (let [s (if (str/starts-with? s "$") (subs s 1) s)]
    (keyword (str/lower-case (str/replace s "_" "-")))))
