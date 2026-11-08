(ns overseer.memory-test
  (:require [clojure.test :refer [deftest is]]
            [overseer.memory :as mem]))

(deftest text-memory-test
  (let [s (mem/make-store)]
    (is (= "" (mem/memory s :a)))
    (mem/set-memory! s :a "hello")
    (is (= "hello" (mem/memory s :a)))
    (mem/append-memory! s :a " world")
    (is (= "hello world" (mem/memory s :a)))
    ;; append to an unset agent starts from the empty string
    (mem/append-memory! s :b "x")
    (is (= "x" (mem/memory s :b)))))

(deftest image-memory-test
  (let [s (mem/make-store)
        img1 {:type :image :path "/a.png"}
        img2 {:type :image :path "/b.png"}]
    (is (= [] (mem/image-memory s :a)))
    (mem/set-image-memory! s :a [img1])
    (is (= [img1] (mem/image-memory s :a)))
    (mem/append-image-memory! s :a [img2])
    (is (= [img1 img2] (mem/image-memory s :a)))))

(deftest agents-test
  (let [s (mem/make-store)]
    (mem/set-memory! s :a "x")
    (mem/set-memory! s :b "y")
    (is (= ["a" "b"] (mem/agents s)))))

(deftest id-normalization-test
  (let [s (mem/make-store)]
    (mem/set-memory! s "a" "str")
    (is (= "str" (mem/memory s :a)))))
