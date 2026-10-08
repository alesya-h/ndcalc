(ns ndcalc.examples-test
  (:require [cljs.test :refer-macros [deftest is]]
            [ndcalc.examples :as examples]
            [ndcalc.engine :as e]))

(defn result [doc c] ((:evaluate (e/make-runtime doc)) c))
(defn number-at [doc c] (:value (result doc c)))

(deftest example-library-evaluates-and-round-trips
  (doseq [{:keys [make title]} examples/examples]
    (let [doc (make) runtime (e/make-runtime doc)
          evaluated (mapv (fn [key]
                            (let [c (e/key-coord key) result ((:evaluate runtime) c)]
                              [(:error result) (:errors ((:format runtime) c result))])) (keys (:cells doc)))
          copy (e/json->document (e/document->json doc))]
      (is (= title (:title doc)))
      (is (<= (count (:cells doc)) 4096))
      (is (every? #(and (nil? (first %)) (empty? (second %))) evaluated) title)
      (is (= (:cells doc) (:cells copy)))
      (is (= (:named doc) (:named copy)))
      (is (= (:dimensions doc) (count (js->clj (:value ((:evaluate runtime) ["axes"])))))))))

(deftest analytical-examples-have-expected-invariants
  (let [doc (examples/heat-document)]
    (is (= (number-at doc [1 2 1 0]) (number-at doc [6 5 1 0])))
    (is (> (number-at doc [3 3 0 0]) (number-at doc [3 3 3 0])))
    (is (> (number-at doc [3 3 1 0]) (number-at doc [3 3 1 2]))))
  (let [doc (examples/modes-document)]
    (is (= 0 (number-at doc [0 2 1 2])))
    (is (= 0 (number-at doc [7 2 1 2])))
    (is (= (number-at doc [2 3 1 0]) (number-at doc [3 2 0 1]))))
  (let [doc (examples/beam-document)]
    (is (> (number-at doc [5 0 0 0 0]) (number-at doc [0 0 0 0 0])))
    (is (> (number-at doc [0 0 0 0 0]) (number-at doc [0 0 3 0 0])))
    (is (> (number-at doc [0 0 0 2 0]) (number-at doc [0 0 0 0 0])))))

(deftest live-business-and-accounting-dependencies
  (let [doc (examples/business-document) c [0 0 0 1]
        runtime (e/make-runtime doc) get-value #(:value ((:evaluate runtime) %))]
    (is (< 0 (get-value (conj c 0))))
    (is (< 0.005 (abs (get-value (conj c 2)))))
    (is (< (abs (- (get-value (conj c 2)) (- (get-value (conj c 0)) (get-value (conj c 1))))) 0.005))
    (let [changed (e/put-cell doc (conj c 0) {:kind "value" :source "100000"})]
      (is (> (number-at changed (conj c 2)) (get-value (conj c 2))))))
  (let [doc (examples/ledger-document) runtime (e/make-runtime doc)]
    (doseq [m (range 6) d (range 3) v (range 2)]
      (is (= 0 (:value ((:evaluate runtime) [m 5 d v])))))
    (let [cash (number-at doc [0 0 0 0])
          changed (e/put-cell doc [0 0 0 0] {:kind "value" :source (str (+ cash 100))})
          runtime (e/make-runtime changed) control ((:evaluate runtime) [0 5 0 0])]
      (is (= 100 (:value control)))
      (is (some #{"imbalance"} (:classes ((:format runtime) [0 5 0 0] control)))))))
