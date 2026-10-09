(ns ndcalc.preview
  (:require [ndcalc.engine :as e]))

(def max-axis-size 32)
(def max-cells 4096)
(def default-options
  {:size [8 8 8] :layout "stack" :tilt 56 :rotation -28
   :zoom 100 :gap 64 :transparency 0 :labels false})
(def hyper-default-options (assoc default-options :size [4 4 4 4] :layout "slices"))
(def option-ranges {:tilt [15 80] :rotation [-180 180] :zoom [25 200] :gap [16 160]
                    :transparency [0 100]})

(defn enqueue-dimension [axes dimension]
  (vec (take-last (count axes) (conj (vec (remove #{dimension} axes)) dimension))))

(defn axis-permutations [axes]
  (if (empty? axes) [[]]
    (vec (for [axis (sort (distinct axes))
               tail (axis-permutations (let [index (.indexOf (clj->js axes) axis)]
                                         (vec (concat (take index axes) (drop (inc index) axes)))))]
           (into [axis] tail)))))

(defn next-permutation [axes]
  (let [choices (axis-permutations axes)
        index (first (keep-indexed #(when (= %2 axes) %1) choices))]
    (nth choices (mod (inc index) (count choices)))))

(defn replace-axis [axes axis dimension]
  (let [other (first (keep-indexed #(when (= %2 dimension) %1) axes))]
    (cond-> (assoc axes axis dimension)
      (some? other) (assoc other (nth axes axis)))))

(defn drag-camera [options dx dy]
  {:tilt (max 15 (min 80 (js/Math.round (- (:tilt options) (* dy 0.35)))))
   :rotation (- (mod (+ 180 (js/Math.round (+ (:rotation options) (* dx 0.35)))) 360) 180)})

(defn validate-shape! [shape]
  (when-not (and (vector? shape) (#{3 4} (count shape))
                 (every? #(and (e/safe-integer? %) (<= 1 % max-axis-size)) shape))
    (e/fail (str "3D/4D window sizes must be integers from 1 to " max-axis-size ".")))
  (when (> (reduce * shape) max-cells)
    (e/fail (str "Previews are limited to " max-cells " cells. Reduce another axis first.")))
  shape)

(defn set-option [options key value]
  (case key
    :size (do (validate-shape! value)
              (when-not (= (count value) (count (:size options))) (e/fail "Wrong preview rank.")))
    :layout (when-not (#{"stack" "slices"} value) (e/fail "Choose Stack or Slices."))
    :labels (when-not (boolean? value) (e/fail "Labels must be enabled or disabled."))
    (let [[low high] (get option-ranges key)]
      (when-not (and low (e/safe-integer? value) (<= low value high))
        (e/fail "Invalid 3D camera option."))))
  (assoc options key value))

(defn restore-options
  ([saved] (restore-options saved default-options))
  ([saved defaults]
   ;; Preferences are optional; ignore invalid/unknown fields from old versions.
   (reduce-kv (fn [options key value]
                (try (set-option options key value) (catch :default _ options)))
              defaults (if (map? saved) saved {}))))

(defn fit-shape [widths]
  (when-not (and (#{3 4} (count widths))
                 (every? #(and (number? %) (js/Number.isInteger %) (pos? %)) widths))
    (e/fail "Expected three or four positive bounds extents."))
  (loop [shape (mapv #(min max-axis-size %) widths)]
    (if (<= (reduce * shape) max-cells) shape
      (let [axis (first (keep-indexed #(when (= %2 (apply max shape)) %1) shape))]
        (recur (update shape axis dec))))))

(defn bounds-shape [bounds axes]
  (mapv #(if (zero? %) 1 (inc (- (e/axis-value (:end bounds) %) (e/axis-value (:start bounds) %)))) axes))

(defn window [doc axes shape fit?]
  (when-not (and (= (count shape) (count axes) (count (set axes)))
                 (every? #(and (e/safe-integer? %) (<= 0 % (:dimensions doc))) axes))
    (e/fail "Preview axes must be distinct dimensions (including null)."))
  (let [shape (mapv #(if (zero? %1) 1 %2) axes shape)
        _ (validate-shape! shape)
        bounds (e/active-bounds doc) c (get-in doc [:view :coord])
        fit? (and fit? bounds)
        starts (mapv (fn [axis size]
                       (let [start (cond (zero? axis) 0
                                         fit? (e/axis-value (:start bounds) axis)
                                         :else (- (e/axis-value c axis) (js/Math.floor (/ (dec size) 2))))]
                         (max (- js/Number.MAX_SAFE_INTEGER)
                              (min (- js/Number.MAX_SAFE_INTEGER (dec size)) start)))) axes shape)]
    {:shape shape :total (reduce * shape) :start starts
     :end (mapv #(+ %1 (dec %2)) starts shape)
     :ranges (mapv #(vec (range %1 (+ %1 %2))) starts shape)
     :clipped? (boolean (and fit? (some true? (map < shape (bounds-shape bounds axes)))))}))

(defn scene [shape options [viewport-width viewport-height]]
  ;; Orthographic projection: fit the rotated plane and the centered Z stack.
  (let [[nx ny nz] shape cell-width (if (:labels options) 128 64)
        row-height (if (:labels options) 50 32)
        width (+ 168 (* nx cell-width)) height (+ 64 (* ny row-height))
        angle (* (:rotation options) (/ js/Math.PI 180))
        tilt (* (:tilt options) (/ js/Math.PI 180))
        projected-width (+ 110 (* (abs (js/Math.cos angle)) width) (* (abs (js/Math.sin angle)) height))
        projected-height (+ 60 (* (js/Math.cos tilt)
                                  (+ (* (abs (js/Math.sin angle)) width) (* (abs (js/Math.cos angle)) height)))
                            (* (js/Math.sin tilt) (dec nz) (:gap options)))]
    {:width width :height height :cell-width cell-width :row-height row-height
     :projected-width projected-width :projected-height projected-height
     :scale (* (/ (:zoom options) 100)
               (min 1 (/ (max 100 (- viewport-width 24)) projected-width)
                      (/ (max 100 (- viewport-height 24)) projected-height)))}))
