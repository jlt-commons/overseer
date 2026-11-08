(ns overseer.sensors
  "The built-in sensors: screen, camera, clipboard, memory, imemory, and
  the audio transcriptions (microphone, screen-audio, all-audio).

  Screen, camera and clipboard capture with the platform's own command
  (`screencapture` / `scrot`, `ffmpeg`, `pbpaste` / `xclip`). The audio
  sensors transcribe through a configurable command (see `:audio` in
  config.edn); without one they answer nil, which the runtime reads as
  \"unavailable\" rather than an error. Memory sensors read the store in
  the capture context.

  `register!` installs them into a registry (the global one by default)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [overseer.memory :as mem]
            [overseer.sensor :as sensor]))

(def ^:private mac? (some? (re-find #"^Mac" (System/getProperty "os.name"))))

(def ^:private tmpdir (System/getProperty "java.io.tmpdir"))

(defn- tmp [ext]
  (str tmpdir "overseer-" (System/nanoTime) "." ext))

(defn- run [cmd]
  (try (let [r (apply sh cmd)]
         (if (zero? (:exit r)) r nil))
       (catch Exception _ nil)))

(defn- capture-file
  "Run a command that writes a file at a trailing `:file` marker; answers
  {:type :image :mime ... :path path}, or nil when the command fails or
  the file is empty."
  [mime cmd]
  (let [path (tmp (if (= mime "image/png") "png" "jpg"))
        args (mapv #(if (= :file %) path %) cmd)]
    (when (run args)
      (let [f (io/file path)]
        (when (and (.exists f) (pos? (.length f)))
          {:type :image :mime mime :path path})))))

;; --- screen / camera / clipboard ------------------------------------------------

(defn- screen-capture [_]
  (if mac?
    (capture-file "image/png" ["screencapture" "-x" :file])
    (capture-file "image/png" ["scrot" :file])))

(defn- camera-capture [_]
  (if mac?
    (capture-file "image/jpeg" ["ffmpeg" "-loglevel" "error" "-f" "avfoundation"
                                "-video_size" "640x480" "-framerate" "30" "-i" "0"
                                "-frames:v" "1" "-y" :file])
    (capture-file "image/jpeg" ["ffmpeg" "-loglevel" "error" "-f" "v4l2"
                                "-i" "/dev/video0" "-frames:v" "1" "-y" :file])))

(defn- clipboard-capture [_]
  (let [r (if mac? (run ["pbpaste"]) (run ["xclip" "-selection" "clipboard" "-o"]))]
    (some-> r :out str/trim)))

;; --- audio transcription --------------------------------------------------------

(defn- transcribe
  "Transcribe audio with the configured command (ctx :transcribe, a vector
  of shell args with :file where the captured wav goes). `kind` is the
  audio source; it selects a future source-specific capture."
  [ctx _kind]
  (when-let [cmd (:transcribe ctx)]
    (let [path (tmp "wav")
          rec (if mac?
                (run ["ffmpeg" "-loglevel" "error" "-f" "avfoundation" "-i" ":0" "-t" "10" "-y" path])
                (run ["arecord" "-d" "10" "-f" "cd" path]))]
      (when rec
        (some-> (run (mapv #(if (= :file %) path %) cmd)) :out str/trim)))))

(defn- microphone-capture [ctx] (transcribe ctx :microphone))
(defn- screen-audio-capture [ctx] (transcribe ctx :screen-audio))
(defn- all-audio-capture [ctx] (transcribe ctx :all-audio))

;; --- memory ----------------------------------------------------------------------

(defn- memory-capture [{:keys [memory agent-id]}]
  (mem/memory memory agent-id))

(defn- imemory-capture [{:keys [memory agent-id]}]
  (vec (mem/image-memory memory agent-id)))

;; --- the built-ins ---------------------------------------------------------------

(defn builtins
  "The built-in sensors as a {id sensor} map, capture fns ready to run."
  []
  {:screen       {:id :screen :description "Screenshot for multimodal models"
                  :kind :image :capture screen-capture}
   :camera       {:id :camera :description "Camera frame for multimodal models"
                  :kind :image :capture camera-capture}
   :clipboard    {:id :clipboard :description "Clipboard contents"
                  :kind :text :capture clipboard-capture}
   :memory       {:id :memory :description "The agent's stored text"
                  :kind :text :capture memory-capture}
   :imemory      {:id :imemory :description "The agent's stored images"
                  :kind :image :capture imemory-capture}
   :microphone   {:id :microphone :description "Microphone transcription"
                  :kind :text :capture microphone-capture}
   :screen-audio {:id :screen-audio :description "Transcription of a shared tab's audio"
                  :kind :text :capture screen-audio-capture}
   :all-audio    {:id :all-audio :description "Mic and screen audio mixed"
                  :kind :text :capture all-audio-capture}})

(defn register!
  "Install the built-ins into a registry (the global one by default)."
  ([] (register! sensor/registry))
  ([reg] (doseq [[_ s] (builtins)] (sensor/register! reg s)) reg))
