(ns overseer.sensors
  "The built-in sensors: screen, camera, clipboard, memory, imemory, and
  the audio transcriptions (microphone, screen-audio, all-audio).

  Screen, camera and clipboard capture with the platform's own command
  (`screencapture` / grim, gnome-screenshot, spectacle, scrot or import;
  `ffmpeg`; `pbpaste` / wl-paste, xclip or xsel), each overridable in
  config.edn ({:screen {:cmd \"scrot\" :args [:file]}}). `screen-ocr` and
  `camera-ocr` read the text in those images with tesseract. The audio
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
  (str (io/file tmpdir (str "overseer-" (System/nanoTime) "." ext))))

(defn- run [cmd]
  (try (let [r (apply sh (map str cmd))]
         (if (zero? (:exit r)) r nil))
       (catch Exception _ nil)))

(defn- on-path? [exe]
  (or (str/includes? exe "/")
      (some #(.canExecute (io/file % exe))
            (str/split (or (System/getenv "PATH") "") (re-pattern java.io.File/pathSeparator)))))

(defn- capture-file
  "Run a command that writes a file at a `:file` marker; answers
  {:type :image :mime ... :path path}, or nil when the command fails or
  the file is empty."
  [mime cmd]
  (let [path (tmp (if (= mime "image/png") "png" "jpg"))
        args (mapv #(if (= :file %) path %) cmd)]
    (when (run args)
      (let [f (io/file path)]
        (when (and (.exists f) (pos? (.length f)))
          {:type :image :mime mime :path path})))))

(defn- file-marker? [x] (contains? #{"file" ":file"} (str x)))

(defn- override
  "The configured command for a sensor, from config.edn
  {:screen {:cmd \"scrot\" :args [:file]}}: a vector, or nil."
  [ctx k]
  (when-let [{:keys [cmd args]} (get-in ctx [:config k])]
    (when cmd (into [cmd] (map #(if (file-marker? %) :file %) args)))))

(defn- first-available
  "The first command of `cmds` whose executable is on the PATH."
  [cmds]
  (first (filter #(on-path? (str (first %))) cmds)))

;; --- screen / camera / clipboard ------------------------------------------------

(def ^:private screen-cmds
  (if mac?
    [["screencapture" "-x" :file]]
    [["grim" :file]                         ; wayland (wlroots)
     ["gnome-screenshot" "-f" :file]
     ["spectacle" "-b" "-n" "-o" :file]
     ["scrot" "-o" :file]
     ["import" "-window" "root" :file]]))   ; ImageMagick (X11)

(defn- screen-capture [ctx]
  (when-let [cmd (or (override ctx :screen) (first-available screen-cmds))]
    (capture-file "image/png" cmd)))

(defn- camera-capture [ctx]
  (capture-file "image/jpeg"
                (or (override ctx :camera)
                    (if mac?
                      ["ffmpeg" "-loglevel" "error" "-f" "avfoundation"
                       "-video_size" "640x480" "-framerate" "30" "-i" "0"
                       "-frames:v" "1" "-y" :file]
                      ["ffmpeg" "-loglevel" "error" "-f" "v4l2"
                       "-i" "/dev/video0" "-frames:v" "1" "-y" :file]))))

(def ^:private clipboard-cmds
  (if mac?
    [["pbpaste"]]
    [["wl-paste" "--no-newline"]
     ["xclip" "-selection" "clipboard" "-o"]
     ["xsel" "--clipboard" "--output"]]))

(defn- clipboard-capture [ctx]
  (when-let [cmd (or (override ctx :clipboard) (first-available clipboard-cmds))]
    (some-> (run cmd) :out str/trim)))

;; --- OCR -------------------------------------------------------------------------

(defn- ocr
  "Text in an image, read with tesseract (or a configured :ocr command
  taking the image at :file and printing the text); nil without one."
  [ctx img]
  (when (and (map? img) (:path img))
    (let [cmd (or (override ctx :ocr)
                  (when (on-path? "tesseract") ["tesseract" :file "stdout"]))]
      (when cmd
        (some-> (run (mapv #(if (= :file %) (:path img) %) cmd)) :out str/trim)))))

(defn- screen-ocr-capture [ctx] (ocr ctx (screen-capture ctx)))
(defn- camera-ocr-capture [ctx] (ocr ctx (camera-capture ctx)))

;; --- audio transcription --------------------------------------------------------

(defn- transcribe
  "Transcribe audio with the configured command (ctx :transcribe, a vector
  of shell args with :file where the captured wav goes). `kind` is the
  audio source; it selects a future source-specific capture."
  [ctx _kind]
  (when-let [cmd (:transcribe ctx)]
    (let [path (tmp "wav")
          rec (if-let [o (override ctx :record)]
                (run (mapv #(if (= :file %) path %) o))
                (if mac?
                  (run ["ffmpeg" "-loglevel" "error" "-f" "avfoundation" "-i" ":0" "-t" "10" "-y" path])
                  (run ["arecord" "-d" "10" "-f" "cd" path])))]
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
                  :kind :text :capture all-audio-capture}
   :screen-ocr   {:id :screen-ocr :description "Text on the screen (tesseract OCR)"
                  :kind :text :capture screen-ocr-capture}
   :camera-ocr   {:id :camera-ocr :description "Text in the camera frame (tesseract OCR)"
                  :kind :text :capture camera-ocr-capture}})

(defn command-sensor
  "A sensor backed by a shell command, the way to add one without writing
  a plugin: {:id :disk :kind :text :cmd [\"df\" \"-h\" \"/\"]} answers the
  trimmed stdout (nil when the command fails); :kind :image with a
  \"file\" / :file arg answers the image the command writes there
  (:mime defaults to image/png)."
  [{:keys [id kind cmd mime description]}]
  (let [id (keyword (name id))
        kind (keyword (name (or kind :text)))
        cmd (mapv #(if (file-marker? %) :file (str %)) (if (string? cmd) [cmd] cmd))]
    (when (empty? cmd)
      (throw (ex-info (str "sensor " (name id) " needs a :cmd") {:type :invalid-sensor})))
    (when (and (= :image kind) (not (some #{:file} cmd)))
      (throw (ex-info (str "image sensor " (name id) " needs a \"file\" argument in :cmd where the image is written")
                      {:type :invalid-sensor})))
    {:id id
     :kind kind
     :description (or description (str/join " " (map #(if (= :file %) "<file>" %) cmd)))
     :cmd cmd
     :capture (if (= :image kind)
                (fn [_] (capture-file (or mime "image/png") cmd))
                (fn [_] (some-> (run cmd) :out str/trim)))}))

(defn register!
  "Install the built-ins into a registry (the global one by default)."
  ([] (register! sensor/registry))
  ([reg] (doseq [[_ s] (builtins)] (sensor/register! reg s)) reg))
