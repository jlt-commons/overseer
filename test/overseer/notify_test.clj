(ns overseer.notify-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [overseer.notify :as notify]
            [ring-chez.adapter :as adapter]))

(def ^:private port 18917)
(def ^:private base (str "http://127.0.0.1:" port))

(defn- with-server
  "Run `f` against a local server recording each request; answers the
  recorded requests. `status` is what the server answers."
  [status f]
  (let [seen (atom [])
        srv (adapter/run-server
             (fn [req]
               (let [b (:body req)
                     body (cond (nil? b) "" (string? b) b :else (slurp b))]
                 (swap! seen conj {:method (:request-method req) :uri (:uri req)
                                   :headers (:headers req) :body body})
                 {:status status :headers {"Content-Type" "application/json"} :body "{\"ok\":true}"}))
             {:port port :host "127.0.0.1"})]
    (try (f) (finally (adapter/stop-server srv)))
    @seen))

(def ^:private system
  {:config {:telegram {:token "T0K" :api base}
            :pushover {:token "APP" :api base}
            :ntfy {:server (str base "/") :token "NT"}}})

(defn- png []
  (let [f (java.io.File/createTempFile "overseer-test" ".png")]
    (spit f "PNGDATA")
    (str f)))

(deftest image-files-test
  (let [p (png)]
    (is (= [p] (map str (notify/image-files {:type :image :path p}))))
    (is (= [p p] (map str (notify/image-files [p {:path p} "/no/such.png" nil]))))
    (is (= [] (notify/image-files nil)))))

(deftest telegram-test
  (let [p (png)
        [msg photo] (with-server 200
                      #(do (notify/send-telegram system 42 "hello")
                           (notify/send-telegram system 42 "look" {:type :image :path p})))]
    (is (= "/botT0K/sendMessage" (:uri msg)))
    (is (= {"chat_id" "42" "text" "hello"} (json/read-str (:body msg))))
    (is (= "/botT0K/sendPhoto" (:uri photo)))
    (is (str/includes? (get-in photo [:headers "content-type"]) "multipart/form-data"))
    (is (every? #(str/includes? (:body photo) %) ["name=\"chat_id\"" "name=\"photo\"" "PNGDATA" "look"]))
    (testing "an image part is typed from its extension"
      (is (str/includes? (:body photo) "Content-Type: image/png")))))

(deftest discord-pushover-ntfy-webhook-test
  (let [p (png)
        [d1 d2 po nt nt-img wh]
        (with-server 200
          #(do (notify/send-discord system (str base "/hook") "hi")
               (notify/send-discord system (str base "/hook") "pic" [p])
               (notify/send-pushover system "USER" "msg" p "Title")
               (notify/send-ntfy system "jobs" "done" nil "Build")
               (notify/send-ntfy system "jobs" "done" p)
               (notify/webhook system (str base "/wh") "plain")))]
    (is (= {"content" "hi"} (json/read-str (:body d1))))
    (is (every? #(str/includes? (:body d2) %) ["payload_json" "files[0]" "PNGDATA"]))
    (is (= "/1/messages.json" (:uri po)))
    (is (every? #(str/includes? (:body po) %) ["APP" "USER" "Title" "name=\"attachment\"" "PNGDATA"]))
    (is (= ["/jobs" :post "done" "Build" "Bearer NT"]
           [(:uri nt) (:method nt) (:body nt) (get-in nt [:headers "title"]) (get-in nt [:headers "authorization"])]))
    (is (= [:put "PNGDATA" "done"] [(:method nt-img) (:body nt-img) (get-in nt-img [:headers "message"])]))
    (is (= {"text" "plain"} (json/read-str (:body wh))))))

(deftest failures-test
  (testing "a non-2xx answer throws with the status"
    (with-server 403
      #(let [e (try (notify/webhook system (str base "/wh") {"a" 1}) nil (catch Exception e e))]
         (is (= {:type :notify-failed :service :webhook :status 403} (ex-data e))))))
  (testing "a missing credential names the config key"
    (is (thrown-with-msg? Exception (re-pattern ":telegram")
                          (notify/send-telegram {:config {}} 1 "x")))))
