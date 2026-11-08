(ns overseer.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [overseer.config :as cfg]))

(deftest parse-args-test
  (testing "flags, values and positionals"
    (is (= {:args ["serve"] "--port" "80" "--self-test" true}
           (cfg/parse-args ["serve" "--port" "80" "--self-test"]))))
  (testing "a bare flag before another flag"
    (is (= {:args [] "--a" true "--b" "x"}
           (cfg/parse-args ["--a" "--b" "x"])))))

(deftest config-dir-test
  (is (= "/cfg" (cfg/config-dir {"OVERSEER_CONFIG_DIR" "/cfg"})))
  (is (= "/h/.config/overseer" (cfg/config-dir {"HOME" "/h"})))
  (is (= "/xdg/overseer" (cfg/config-dir {"XDG_CONFIG_HOME" "/xdg" "HOME" "/h"}))))

(deftest load-config-test
  (let [dir (str (System/getProperty "java.io.tmpdir") "/overseer-test-" (System/nanoTime))]
    (.mkdir (io/file dir))
    (spit (str dir "/config.edn") "{:port 9999 :data \"/data\"}")
    (is (= {:port 9999 :data "/data"} (cfg/load-config dir)))
    (is (= {} (cfg/load-config (str dir "/missing"))))))

(deftest setting-test
  (testing "cli beats env beats config beats default"
    (let [base {:config {:port "8081"} :env {}}]
      (is (= "80" (cfg/setting (assoc base :opts {"--port" "80"}) "--port" "OVERSEER_PORT" :port "8080")))
      (is (= "8082" (cfg/setting (assoc base :env {"OVERSEER_PORT" "8082"} :opts {}) "--port" "OVERSEER_PORT" :port "8080")))
      (is (= "8081" (cfg/setting base "--port" "OVERSEER_PORT" :port "8080")))
      (is (= "8080" (cfg/setting (assoc base :config {}) "--port" "OVERSEER_PORT" :port "8080"))))))

(deftest plugins-dir-test
  (is (= "/cfg/plugins" (cfg/plugins-dir {"OVERSEER_CONFIG_DIR" "/cfg"} {}))))
