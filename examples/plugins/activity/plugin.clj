(ns overseer.plugins.activity
  "An example plugin: a custom sensor, a custom tool, and an agent that
  watches the clipboard and records activity to memory.

  Copy this directory to ~/.config/overseer/plugins/ and it loads when
  overseer starts."
  (:require [clojure.string :as str]
            [overseer.plugin :as p]))

;; --- a custom sensor: disk usage, read from `df` -------------------------------

(p/register-sensor!
 {:id :disk-usage
  :kind :text
  :description "Free disk space on the main volume"
  :capture (fn [_]
             (let [{:keys [exit out]} (clojure.java.shell/sh "df" "-h" "/")]
               (when (zero? exit)
                 (str/trim (last (str/split-lines out))))))})

;; --- a custom tool: append a line to a log file --------------------------------

(p/register-tool!
 {:name "log"
  :doc "Append a line to ~/.config/overseer/log.txt"
  :fn (fn [system line]
        (spit (str (overseer.config/config-dir) "/log.txt")
              (str line "\n") :append true)
        nil)})

;; --- an agent: records clipboard activity when the model says to ----------------

(defonce ^:private last-clipboard (atom nil))

(p/register-sensor!
 {:id :clipboard-change
  :kind :text
  :description "The clipboard, only when it changed since the last capture"
  :capture (fn [_]
             (let [v (overseer.sensor/capture :clipboard {})]
               (when (and (seq v) (not= v @last-clipboard))
                 (reset! last-clipboard v)
                 v)))})

(p/register-agent!
 {:id :activity-tracker
  :name "Activity Tracker"
  :description "Records clipboard activity to memory"
  :prompt (str "You watch the user's clipboard.\n"
               "Clipboard now: $CLIPBOARD\n"
               "Previous notes:\n$MEMORY\n\n"
               "Is there new activity worth recording?")
  :sensors [:clipboard-change]
  :decision {:type :noul :instructions "Is there new activity worth recording?" :threshold 0.5}
  :interval-ms 15000
  :code (fn [ctx]
          (when (and (:act? ctx) (seq (:clipboard ctx)))
            ((:call ctx) "append-memory"
             (str "[" ((:call ctx) "time") "] " (:clipboard ctx)))))})
