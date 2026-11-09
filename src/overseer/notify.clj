(ns overseer.notify
  "Messaging tools over HTTP (jolt.http-client, from jolt-lang/http-client):
  Discord, Telegram, Pushover, ntfy, and a generic JSON webhook. Each mirrors Observer's tool of the same name and takes
  optional images: a captured image ({:type :image :path ..}), a file
  path, or a list of either, e.g. (:screen ctx) or (:images ctx).

  Credentials live in config.edn:

    {:telegram {:token \"123:abc\"}          ; a bot token from @BotFather
     :pushover {:token \"app-token\"}        ; a Pushover application token
     :ntfy     {:server \"https://ntfy.sh\"   ; or your own ntfy server
                :token nil}}                 ; an access token, when it needs one

  Discord takes the webhook URL as its argument. Every tool answers the
  service's response body, and throws ex-info {:type :notify-failed} on a
  non-2xx status or a missing credential."
  (:require [jolt.http-client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- helpers --------------------------------------------------------------------------

(defn- fail [msg & [data]]
  (throw (ex-info msg (merge {:type :notify-failed} data))))

(defn- setting [system service k & [default]]
  (or (get-in system [:config service k]) default))

(defn- required [system service k]
  (or (setting system service k)
      (fail (str "set " (pr-str {service {k "..."}}) " in config.edn to use " (name service)))))

(defn image-files
  "The image files in `images`: a captured image map, a path string, a
  File, or a list of those. Missing files are dropped."
  [images]
  (->> (cond (nil? images) []
             (or (map? images) (string? images) (instance? java.io.File images)) [images]
             (sequential? images) images
             :else [])
       (keep (fn [img]
               (let [p (cond (map? img) (:path img)
                             (string? img) img
                             (instance? java.io.File img) (str img))]
                 (when p (let [f (io/file p)] (when (.isFile f) f))))))
       vec))

(defn- file-part
  "A multipart part for the file `f`; jolt.http-client types it from its
  extension (Files/probeContentType)."
  [field f]
  {:name field :content f :file-name (.getName f)})

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (subs s 0 n) s)))

(defn- send! [service method url opts]
  (let [r (try (http/request (merge {:method method :url url :throw false} opts))
               (catch Exception e
                 (fail (str (name service) ": " (or (ex-message e) (str e))) {:service service})))]
    (if (<= 200 (:status r) 299)
      (:body r)
      (fail (str (name service) " answered " (:status r) ": " (clip (:body r) 300))
            {:service service :status (:status r)}))))

(defn- json-body [m]
  {:headers {"Content-Type" "application/json"} :body (json/write-str m)})

;; --- the tools ------------------------------------------------------------------------

(defn send-discord
  "(send-discord webhook-url message images?) posts to a Discord webhook,
  attaching any images."
  [_system webhook message & [images]]
  (let [content (clip message 2000)
        files (image-files images)]
    (if (empty? files)
      (send! :discord :post webhook (json-body {"content" content}))
      (send! :discord :post webhook
             {:multipart (into [{:name "payload_json" :content (json/write-str {"content" content})
                                 :mime-type "application/json"}]
                               (map-indexed (fn [i f] (file-part (str "files[" i "]") f)) files))}))))

(defn send-telegram
  "(send-telegram chat-id message images?) sends through the bot in
  config :telegram :token; each image is sent as a photo, the first
  captioned with the message."
  [system chat-id message & [images]]
  (let [base (str (setting system :telegram :api "https://api.telegram.org")
                  "/bot" (required system :telegram :token))
        files (image-files images)]
    (if (empty? files)
      (send! :telegram :post (str base "/sendMessage")
             (json-body {"chat_id" (str chat-id) "text" (clip message 4096)}))
      (last (map-indexed
             (fn [i f]
               (send! :telegram :post (str base "/sendPhoto")
                      {:multipart (cond-> [{:name "chat_id" :content (str chat-id)}
                                           (file-part "photo" f)]
                                    (zero? i) (conj {:name "caption" :content (clip message 1024)}))}))
             files)))))

(defn send-pushover
  "(send-pushover user-key message images? title?) sends a Pushover
  notification with the app token in config :pushover :token (Pushover
  takes one attachment: the first image)."
  [system user message & [images title]]
  (let [f (first (image-files images))]
    (send! :pushover :post (str (setting system :pushover :api "https://api.pushover.net") "/1/messages.json")
           {:multipart (cond-> [{:name "token" :content (required system :pushover :token)}
                                {:name "user" :content (str user)}
                                {:name "message" :content (clip message 1024)}]
                         title (conj {:name "title" :content (clip title 250)})
                         f (conj (file-part "attachment" f)))})))

(defn send-ntfy
  "(send-ntfy topic message images? title?) publishes to an ntfy topic on
  config :ntfy :server (https://ntfy.sh by default); with an image, the
  first is attached."
  [system topic message & [images title]]
  (let [url (str (str/replace (setting system :ntfy :server "https://ntfy.sh") (re-pattern "/+$") "")
                 "/" topic)
        token (setting system :ntfy :token)
        f (first (image-files images))
        headers (cond-> {}
                  title (assoc "Title" (str title))
                  token (assoc "Authorization" (str "Bearer " token)))]
    (if f
      (send! :ntfy :put url {:headers (assoc headers "Filename" (.getName f) "Message" (clip message 4096))
                             :body f})
      (send! :ntfy :post url {:headers headers :body (str message)}))))

(defn webhook
  "(webhook url payload) POSTs `payload` as JSON (a map, or a string sent
  as {\"text\" payload}): Slack and Mattermost incoming webhooks, Home
  Assistant, n8n, your own service."
  [_system url payload]
  (send! :webhook :post url (json-body (if (map? payload) payload {"text" (str payload)}))))

(defn builtins
  "The messaging tools as a {name tool} map."
  []
  {:send-discord  {:name "send-discord" :doc "Post to a Discord webhook: (send-discord webhook message images?)" :fn send-discord}
   :send-telegram {:name "send-telegram" :doc "Telegram message via the configured bot: (send-telegram chat-id message images?)" :fn send-telegram}
   :send-pushover {:name "send-pushover" :doc "Pushover notification: (send-pushover user-key message images? title?)" :fn send-pushover}
   :send-ntfy     {:name "send-ntfy" :doc "Publish to an ntfy topic: (send-ntfy topic message images? title?)" :fn send-ntfy}
   :webhook       {:name "webhook" :doc "POST JSON to a URL: (webhook url payload)" :fn webhook}})
