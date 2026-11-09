(ns overseer.sensor-test
  (:require [clojure.test :refer [deftest is]]
            [overseer.memory :as mem]
            [overseer.sensor :as sensor]))

(deftest placeholder-test
  (is (= "$SCREEN" (sensor/placeholder :screen)))
  (is (= "$SCREEN_AUDIO" (sensor/placeholder :screen-audio)))
  (is (= "$ALL_AUDIO" (sensor/placeholder :all-audio))))

(deftest placeholder-round-trip-test
  ;; a lowercase id survives placeholder -> sensor-id-of (the $ prefix is stripped)
  (doseq [id [:screen :camera :clipboard :memory :imemory :microphone :screen-audio :all-audio]]
    (is (= id (sensor/sensor-id-of (sensor/placeholder id)))))
  ;; the token form works with or without the leading $
  (is (= :clipboard (sensor/sensor-id-of "$CLIPBOARD")))
  (is (= :clipboard (sensor/sensor-id-of "CLIPBOARD"))))

(deftest register-and-capture-test
  (let [reg (atom {})]
    (sensor/register! reg {:id :foo :kind :text :capture (fn [_] "bar")})
    (is (= "bar" (sensor/capture reg :foo {})))
    (is (nil? (sensor/capture reg :missing {})))
    (is (thrown? Exception (sensor/register! reg {:id :bad}))))
  ;; a throwing capture is caught into an error value by default
  (let [reg (atom {})]
    (sensor/register! reg {:id :boom :capture (fn [_] (throw (ex-info "nope" {})))})
    (is (= {:type :error :message "nope"} (sensor/capture reg :boom {})))
    (is (thrown? Exception (sensor/capture reg :boom {} true)))))

(deftest render-value-test
  (is (= "text" (sensor/render-value "text")))
  (is (= "" (sensor/render-value nil)))
  (is (= "[image:image/png]" (sensor/render-value {:type :image :mime "image/png" :path "/x"})))
  (is (= "" (sensor/render-value {:type :error :message "x"}))))

(deftest render-test
  (let [store (mem/make-store)]
    (mem/set-memory! store :a "stored")
    (is (= "clip is hello, mem is stored"
           (sensor/render "clip is $CLIPBOARD, mem is $MEMORY"
                          {:clipboard "hello"}
                          {:memory store :agent-id :a})))
    (is (= "other agent: stored"
           (sensor/render "other agent: $MEMORY@a" {} {:memory store :agent-id :b})))))

(deftest render-image-placeholder-test
  (let [store (mem/make-store)]
    (mem/set-memory! store :a "m")
    (is (= "see [image:image/png] and m"
           (sensor/render "see $SCREEN and $MEMORY"
                          {:screen {:type :image :mime "image/png" :path "/s.png"}}
                          {:memory store :agent-id :a})))))

(deftest render-leaves-unknown-placeholders-test
  (is (= "echo $HOME and x" (sensor/render "echo $HOME and $CLIPBOARD" {:clipboard "x"} {})))
  ;; a captured sensor that answered nil renders empty
  (is (= "clip: " (sensor/render "clip: $CLIPBOARD" {:clipboard nil} {}))))
