# Overseer

A local-first AI agent that uses sensors to watch your screen, camera, or anything you want to plug in, and takes action when an event happens. Built in [Jolt](https://github.com/jolt-lang/jolt) with [Lev](https://github.com/jlt-commons/lev) as the
decision engine. Everything runs locally.

Each agent is a loop: **sensors → decision → code**.

```
sensors            capture the world ($SCREEN, $CLIPBOARD, …)
decision          Lev answers typed questions over the rendered prompt
code              a Jolt function that acts on the answers
```

## Quick start

```bash
# list agents, sensors and tools
jolt -m overseer.core list

# ask Lev one question over some text (needs a prepared data dir)
OVERSEER_DATA=/path/to/lev/data jolt -m overseer.core decide "the build failed with OOM"

# run all registered agents forever
OVERSEER_DATA=/path/to/lev/data jolt -m overseer.core run

# serve the HTTP API
OVERSEER_DATA=/path/to/lev/data jolt -m overseer.core serve
```

## The loop

An agent is a map:

```clojure
{:id :focus-tracker
 :name "Focus Tracker"
 :prompt "You watch the screen.\n$SCREEN\n$MEMORY\nIs there new activity?"
 :sensors [:screen :clipboard]          ; captured each pass
 :decision {:type :noul                 ; a Lev question (noul | choice | score)
            :instructions "Is there new activity?"
            :threshold 0.8}             ; act when noul > threshold
 :interval-ms 60000
 :code (fn [ctx] ...)}                  ; runs with the decision + captures
```

Every interval, overseer captures the agent's sensors, renders the `$SENSOR`
placeholders in the prompt into the Lev `state`, asks the decision engine the
agent's typed question(s), and calls `:code` with a context:

| ctx key | what it is |
| --- | --- |
| `:agent-id` | the running agent's id |
| `:decision` | the typed answers, `{"act" {:type :noul :noul 0.94 …}}` |
| `:act?` | the gate over `:decision` (`:threshold` / `:act` / `:min`) |
| `:captured` | `{sensor-id value}` for this pass |
| `:screen` `:camera` `:clipboard` `:microphone` `:screen-audio` `:all-audio` | flattened sensor values |
| `:memory` `:imemory` | this agent's stored text / images |
| `:images` | every image the sensors returned |
| `:prompt` | the rendered state the model read |
| `:call` | `(call "tool" arg …)` runs a tool with this agent as default |

### Decision models

Lev answers typed questions over a text state — no sessions, no memory, one
stateless pass per call. Overseer maps an agent's `:decision` to a Lev question:

- **noul** — a yes/no: `{"type" "noul" "instructions" "…"}`, answers
  `noul ∈ [0,1]`, `confidence`. Act when `noul > threshold`.
- **choice** — `{"type" "choice" "instructions" "…" "criteria" {opt desc}}`,
  answers `choice`, `probabilities`, `confidence`. Act when `choice` is in `:act`.
- **score** — `{"type" "score" "instructions" "…" "criteria" [low … high]}`,
  answers `score`, `legend`, `probabilities`. Act when `score >= min`.

`{:questions {qid qdef …}}` asks several at once; `:decision` is also where a
caller passes Lev's own options (`:model`, `:constraints`, `:thinking`).

## Sensors

The built-ins:

| Sensor | Description |
| --- | --- |
| `$SCREEN` | Screenshot (`screencapture` / `scrot`) |
| `$CAMERA` | Camera frame (`ffmpeg` avfoundation / v4l2) |
| `$CLIPBOARD` | Clipboard contents (`pbpaste` / `xclip`) |
| `$MEMORY` / `$MEMORY@agent_id` | An agent's stored text |
| `$IMEMORY` / `$IMEMORY@agent_id` | An agent's stored images |
| `$MICROPHONE` | Mic transcription (a configured `:transcribe` command) |
| `$SCREEN_AUDIO` | Transcription of a shared tab's audio |
| `$ALL_AUDIO` | Mic and screen audio mixed |

A sensor is `{:id kw :kind :image|:text :capture (fn [ctx] value)}`; `capture`
returns a string, `{:type :image :mime … :path …}`, or nil (unavailable). Screen,
camera and clipboard capture with the platform's own command; the audio sensors
transcribe through a shell command you configure, and answer nil without one.

## Tools

`$` memory — `get-memory`, `set-memory`, `append-memory`,
`get-image-memory`, `set-image-memory`, `append-image-memory` (optional
`agentId`, defaults to the running agent) — and agent control `start-agent` /
`stop-agent`, `time`, `sleep`, desktop `notify`, `sound`, and a shell `exec`.
Plugins add more.

## Plugins

Plugins live in `~/.config/overseer/plugins/<plugin>/` — one directory per
plugin, every `.clj` file under it is `load-file`d at startup. A plugin is a
Jolt namespace that registers sensors, tools and agents:

```clojure
(ns overseer.plugins.activity
  (:require [overseer.plugin :as p]))

(p/register-sensor! {:id :disk-usage :kind :text
                     :capture (fn [_] …)})
(p/register-tool!   {:name "log" :fn (fn [system line] …)})
(p/register-agent!  {:id :activity-tracker :prompt "…" :code (fn [ctx] …)})
```

See `examples/plugins/activity/plugin.clj` for a complete, runnable example, and
`examples/config.edn` for the config file.

## Configuration

`~/.config/overseer/config.edn` (CLI flag > env var > config > default):

```clojure
{:data "/path/to/lev/data"       ; prepared Lev data root (jolt prepare)
 :thinkers {"name" {:model "x.gguf"}}
 :default-model "english"
 :port 8080 :host "127.0.0.1" :api-key nil
 :audio {:transcribe ["whisper-cli" "-m" "base.en.bin" "-f" :file]}
 :agents {"my-agent" {:prompt "…" :decision {…} :sensors […] :code my.plugin/handle}}}
```

Environment: `OVERSEER_DATA`, `OVERSEER_PORT`, `OVERSEER_HOST`,
`OVERSEER_API_KEY`, `OVERSEER_PLUGINS_DIR`, `OVERSEER_CONFIG_DIR`.

## HTTP API

`jolt -m overseer.core serve` exposes (ring + ruuter):

```
GET  /health
GET  /agents            GET  /agents/:id
POST /agents/:id/start  POST /agents/:id/stop  POST /agents/:id/run
GET  /sensors           GET  /tools
POST /capture/:sensor
POST /decide            {"state" .., "questions" {..}}
GET  /memory/:agent     PUT  /memory/:agent
```

## Using Lev directly

Overseer is also a library. The decision engine is two functions:

```clojure
(require '[overseer.decision :as d])
(def engine (d/make-engine (d/make-router {:data "/path/to/lev/data"})))
(engine "the build failed with OOM" {"urgent" (d/noul "Is this urgent?")})
```

`d/noul`, `d/choice`, `d/score` build questions; `d/answers`, `d/noul-of`,
`d/choice-of`, `d/score-of`, `d/confidence-of` read the result.

## Development

```bash
jolt test            # the clojure.test suite
jolt metrics         # jolt-metrics report (quality)
clj-kondo --lint src test
jolt binary          # build ./overseer
```

The binary bakes Lev (source + the native kernels, llama.cpp and MLX) in, so it
needs only the prepared `data/` dir and the OS libs the HTTP adapter uses (zlib,
OpenSSL). Tests never load a model: the agent loop is exercised against a fake
engine.

