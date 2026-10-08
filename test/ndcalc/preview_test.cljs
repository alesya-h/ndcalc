(ns ndcalc.preview-test
  (:require [cljs.test :refer-macros [deftest is]]
            [ndcalc.preview :as p]
            [ndcalc.demo :as demo]
            [ndcalc.engine :as e]))

(deftest dimension-queues-and-explicit-axis-swaps
  (is (= [1 2 3] (p/enqueue-dimension [1 2 3] 3)))
  (is (= [2 3 1] (p/enqueue-dimension [1 2 3] 1)))
  (is (= [1 3 2] (p/enqueue-dimension [1 2 3] 2)))
  (is (= [2 3 5] (p/enqueue-dimension [1 2 3] 5)))
  (is (= [3 2 1] (p/replace-axis [1 2 3] 0 3)))
  (is (= [1 5 3] (p/replace-axis [1 2 3] 1 5))))

(deftest drag-camera-wraps-rotation-and-clamps-tilt
  (is (= {:tilt 42 :rotation 0} (p/drag-camera p/default-options 80 40)))
  (is (= 15 (:tilt (p/drag-camera p/default-options 0 1000))))
  (is (= 80 (:tilt (p/drag-camera p/default-options 0 -1000))))
  (is (= -174 (:rotation (p/drag-camera (assoc p/default-options :rotation 179) 20 0))))
  (is (= 174 (:rotation (p/drag-camera (assoc p/default-options :rotation -179) -20 0))))
  (is (= 35 (:transparency (p/restore-options {:transparency 35}))))
  (is (= 0 (:transparency (p/restore-options {:transparency 101}))))
  (is (thrown? js/Error (p/set-option p/default-options :transparency -1))))

(deftest sizes-and-fitting-are-bounded
  (is (= [8 8 8] (p/validate-shape! [8 8 8])))
  (is (= [16 16 16] (p/fit-shape [1000 1000 1000])))
  (is (= [32 2 32] (p/fit-shape [1000 2 1000])))
  (is (= [7 2 13] (p/fit-shape [7 2 13])))
  (doseq [shape [[0 1 1] [33 1 1] [1.5 1 1] [1 1] [32 32 32]]]
    (is (thrown? js/Error (p/validate-shape! shape))))
  (is (thrown? js/Error (p/fit-shape [0 1 1]))))

(deftest windows-support-remapped-axes-and-negative-coordinates
  (let [doc (assoc-in (demo/blank-document "negative" 5) [:view :coord] [-5 17 -3 2 4])
        window (p/window doc [4 1 3] [4 6 8] false)]
    (is (= [1 -7 -6] (:start window)))
    (is (= [4 -2 1] (:end window)))
    (is (= [4 6 8] (mapv count (:ranges window))))
    (is (= 192 (:total window)))
    (is (false? (:clipped? window)))
    (doseq [axes [[1 1 2] [0 1 2] [1 2 6] [1 2]]]
      (is (thrown? js/Error (p/window doc axes [4 6 8] false))))))

(deftest fitting-keeps-exact-bounds-or-explicitly-reports-clipping
  (let [doc (-> (demo/blank-document "bounds" 5)
                (e/put-cell [-9 0 -2 7 5] {:kind "value" :source "1"})
                (e/put-cell [3 2 4 8 9] {:kind "value" :source "2"}))
        window (p/window doc [3 4 1] [7 2 13] true)]
    (is (= [-2 7 -9] (:start window)))
    (is (= [4 8 3] (:end window)))
    (is (false? (:clipped? window)))
    (is (:clipped? (p/window doc [3 4 1] [7 1 13] true)))))

(deftest windows-respect-safe-integer-boundaries
  (doseq [edge [js/Number.MAX_SAFE_INTEGER (- js/Number.MAX_SAFE_INTEGER)]]
    (let [doc (assoc-in (demo/blank-document "edge" 3) [:view :coord] [edge edge edge])
          window (p/window doc [1 2 3] [32 16 8] false)]
      (is (= 4096 (:total window)))
      (is (= [32 16 8] (mapv count (:ranges window))))
      (is (every? e/safe-integer? (mapcat identity (:ranges window))))
      (is (every? #(some #{edge} %) (:ranges window))))))

(deftest preferences-are-validated-and-camera-fits
  (let [options (p/restore-options {:size [32 32 32] :tilt 75 :zoom 125 :labels true
                                    :layout "slices" :rotation -99 :gap -1 :unknown 42})
        scene (p/scene [8 8 8] (assoc options :zoom 100) [1000 600])]
    (is (= [8 8 8] (:size options)))
    (is (= 75 (:tilt options)))
    (is (= 125 (:zoom options)))
    (is (= 64 (:gap options)))
    (is (= "slices" (:layout options)))
    (is (not (contains? options :unknown)))
    (is (= 1024 (:width scene)))
    (is (= 400 (:height scene)))
    (is (< 0 (:scale scene) 1))
    (is (thrown? js/Error (p/set-option options :labels "yes")))))
