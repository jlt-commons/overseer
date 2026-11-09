(ns overseer.plugins.activity
  "An example plugin: a custom sensor, a custom tool, and an agent that
  watches the clipboard and records activity to memory.

  Copy this directory to ~/.config/overseer/plugins/ and it loads when
  overseer starts."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [overseer.config :as config]
            [overseer.plugin :as p]
            [overseer.sensor :as sensor]))

;; --- a custom sensor: disk usage, read from `df` -------------------------------

(p/register-sensor!
 {:id :disk-usage
  :kind :text
  :description "Free disk space on the main volume"
  :capture (fn [_]
             (let [{:keys [exit out]} (shell/sh "df" "-h" "/")]
               (when (zero? exit)
                 (str/trim (last (str/split-lines out))))))})

;; --- a custom tool: append a line to a log file --------------------------------

(p/register-tool!
 {:name "log"
  :doc "Append a line to ~/.config/overseer/log.txt"
  :fn (fn [system line]
        (spit (str (config/config-dir) "/log.txt")
              (str line "\n") :append true)
        nil)})

;; --- an agent: records clipboard activity when the model says to ----------------

(defonce ^:private last-clipboard (atom nil))

(p/register-sensor!
 {:id :clipboard-change
  :kind :text
  :description "The clipboard, only when it changed since the last capture"
  :capture (fn [ctx]
             (let [v (sensor/capture :clipboard ctx)]
               (when (and (seq v) (not= v @last-clipboard))
                 (reset! last-clipboard v)
                 v)))})

(p/register-agent!
 {:id :activity-tracker
  :name "Activity Tracker"
  :description "Records clipboard activity to memory"
  ;; $CLIPBOARD_CHANGE is the :clipboard-change sensor registered above: a prompt's
  ;; placeholders are what the agent captures each pass
  :prompt (str "You watch the user's clipboard.\n"
               "Clipboard now: $CLIPBOARD_CHANGE\n"
               "Previous notes:\n$MEMORY\n\n"
               "Is there new activity worth recording?")
  :decision {:type :noul :instructions "Is there new activity worth recording?" :threshold 0.5}
  :interval-ms 15000
  :code (fn [ctx]
          (let [clip (get-in ctx [:captured :clipboard-change])]
            (when (and (:act? ctx) (seq clip))
              ((:call ctx) "append-memory"
               (str "[" ((:call ctx) "time") "] " clip "\n")))))})
