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
| `$SCREEN_OCR` / `$CAMERA_OCR` | Text in the screenshot / camera frame (tesseract) |

A sensor is `{:id kw :kind :image|:text :capture (fn [ctx] value)}`; `capture`
returns a string, `{:type :image :mime … :path …}`, or nil (unavailable). Screen,
camera and clipboard capture with the platform's own command (on Linux the first
of grim, gnome-screenshot, spectacle, scrot or ImageMagick `import`, and wl-paste,
xclip or xsel). Override one in config.edn with `{:screen {:cmd "scrot" :args [:file]}}`.
The audio sensors transcribe through a shell command you configure, and answer
nil without one.

Lev decides over text only. An image sensor shows up in the state as a marker
such as `[image:image/png]`, and the image reaches your `:code` (`:screen`,
`:images`). It does not reach the decision. To decide on what is on screen, use
`$SCREEN_OCR` or a text sensor.

## Tools

`$` memory — `get-memory`, `set-memory`, `append-memory`,
`get-image-memory`, `set-image-memory`, `append-image-memory` (optional
`agentId`, defaults to the running agent) — and agent control `start-agent` /
`stop-agent`, `time`, `sleep`, desktop `notify`, `sound`, and a shell `exec`.

Messaging over HTTP ([jolt-lang/http-client](https://github.com/jolt-lang/http-client)).
`images` is optional: a captured image such as `(:screen ctx)`, a file path, or
a list of either.

| Tool | Call | Needs in config.edn |
| --- | --- | --- |
| `send-discord` | `(send-discord webhook-url message images?)` | nothing |
| `send-telegram` | `(send-telegram chat-id message images?)` | `{:telegram {:token "bot-token"}}` |
| `send-pushover` | `(send-pushover user-key message images? title?)` | `{:pushover {:token "app-token"}}` |
| `send-ntfy` | `(send-ntfy topic message images? title?)` | optional `{:ntfy {:server "https://ntfy.example" :token "…"}}` (default ntfy.sh) |
| `webhook` | `(webhook url payload)`: a map as JSON, a string as `{"text" …}` | nothing |

```clojure
(fn [ctx]
  (when (:act? ctx)
    ((:call ctx) "send-telegram" "12345678" "Someone is at the door" (:camera ctx))))
```

A non-2xx answer throws, so the failure shows up in the agent's runs. Plugins
add more tools with `register-tool!`, and can use the same client,
`jolt.http-client` (clj-http-lite's API plus babashka.http-client's request
options such as `:multipart`).

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

`jolt -m overseer.core serve` exposes (ring + ruuter). It starts every
registered agent; pass `--no-start` to leave them stopped.

```
GET    /health
GET    /agents                  POST /agents          (create)
GET    /agents/:id              PUT  /agents/:id      (edit; stop it first)
DELETE /agents/:id              (stops it, deletes it and its memory)
POST   /agents/:id/start        POST /agents/:id/stop
POST   /agents/:id/run          one pass now, answers the run
GET    /agents/:id/runs?limit=N recent passes, newest first
GET    /sensors                 POST /sensors         DELETE /sensors/:id
POST   /capture/:sensor
GET    /tools
POST   /decide                  {"state" .., "questions" {..}}
GET    /memory/:agent           PUT /memory/:agent    DELETE /memory/:agent
POST   /memory/:agent/append    {"memory" ".."} and/or {"imemory" ["/path.png"]}
```

With an `:api-key` every route except `/health` needs `Authorization: Bearer
<key>`. Errors answer `{"error" msg}` with 400 (bad JSON), 401, 404, 409
(conflict), 422 (invalid request) or 503 (no model).

### Creating a micro-agent

```bash
curl -XPOST localhost:8080/agents -d '{
  "id": "build_watch",
  "name": "Build watcher",
  "prompt": "Build log:\n$BUILD_LOG\nEarlier notes:\n$MEMORY",
  "decision": {"type": "noul", "instructions": "Is the build still running?", "threshold": 0.5},
  "loop_interval_seconds": 30,
  "only_on_change": false,
  "code": "(fn [ctx] (let [d (get-in ctx [:decision \"act\"])] (when (get d \"dropped\") ((:call ctx) \"notify\" \"Build\" \"finished\") ((:call ctx) \"stop-agent\"))))"
}'
```

- The sensors captured each pass are the prompt's placeholders plus any
  listed in `"sensors"`. A `$WORD` that names no sensor (`$HOME`) is left as is.
- `"code"` is Jolt source evaluating to `(fn [ctx] ...)`, checked when the
  agent is saved. Without a `"decision"` the agent asks a noul.
- `"decision"` takes `"questions"` for several typed questions at once,
  and the gate keys `threshold` (noul), `act` (choice) and `min` (score).
- `"only_on_change": true` reuses the last decision while the rendered
  state is unchanged (the code still runs).
- Agents created over the API live in `<config-dir>/agents/<id>.edn` and
  load at startup. Agents whose `:code` is a fn (from plugins) are listed
  as `"editable": false`.

### The feedback loop

Each pass is recorded (`GET /agents/:id/runs`): the rendered state, the
captures, the decision, `act`, and any error. A failing pass is logged and
recorded, and the loop keeps going. A single noul answer also carries
Observer's run trend: `start` (the mean noul of the first 3 passes since
the agent started) and `dropped` (true once noul has stayed 0.15 below
`start` for 3 passes in a row). Use it for "tell me when this finishes".
Code can write `$MEMORY` (`append-memory`) for the next pass to read, and
can stop its own agent (`stop-agent` with no id).

### Adding a sensor without a plugin

```bash
curl -XPOST localhost:8080/sensors -d '{"id": "build-log", "cmd": ["tail", "-n", "40", "/tmp/build.log"]}'
# image sensors write to the "file" argument:
curl -XPOST localhost:8080/sensors -d '{"id": "window", "kind": "image", "cmd": ["grim", "file"]}'
```

The placeholder is the id uppercased with `_` for `-` (`$BUILD_LOG`). Command
sensors live in `<config-dir>/sensors/<id>.edn`.

### Memory

Agent memory is kept in `<config-dir>/memory.edn` (config `:memory-file`;
`false` keeps it in memory only), so it survives a restart.

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


## License

EPL 2.0, the same license as Jolt and Clojure. See [LICENSE](LICENSE).
