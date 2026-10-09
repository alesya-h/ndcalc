(ns ndcalc.layout
  (:require [clojure.string :as str]
            [ndcalc.engine :as e]))

(defonce canvas (atom nil))
(defn text-width [text]
  (if (exists? js/document)
    (let [context (or @canvas (reset! canvas (.getContext (.createElement js/document "canvas") "2d")))]
      (set! (.-font context) "15px 'IBM Plex Mono', monospace")
      (apply max 0 (map #(.-width (.measureText context %)) (str/split (str text) #"\n"))))
    (* 9 (apply max 0 (map count (str/split (str text) #"\n"))))))
(defn natural-width [text formula? square]
  (max square (min 400 (js/Math.ceil (+ 16 (if formula? 16 0) (text-width text))))))
(defn requested-width [doc runtime target labels? square]
  (or (e/cell-width doc target)
      (if-let [cell (e/cell-at doc target)]
        (if labels?
          (let [result ((:evaluate runtime) target)]
            (natural-width (if (:error result) (str "#ERROR " (:error result)) (e/stringify (:value result)))
                           (= "formula" (:kind cell)) square))
          square)
        square)))
(defn column-widths [doc runtime origin axes xs labels? square]
  (let [x (first axes) varying (set axes)
        widths (into {} (map (fn [a] [a (max square (natural-width a false square)
                                             (requested-width doc runtime {:hyperplane [x a]} true square))]) xs))]
    (mapv (reduce (fn [widths [key _]]
                    (let [c (e/key-coord key) numeric? (and (not (e/named? c)) (not (e/hyperplane? c)))
                          a (when numeric? (e/axis-value c x))]
                      (if (and numeric? (contains? widths a)
                               (every? (fn [i] (or (contains? varying (inc i)) (= (nth c i) (nth origin i))))
                                       (range (:dimensions doc))))
                        (update widths a max (requested-width doc runtime c labels? square)) widths)))
                  widths (concat (:cells doc) (:cell-widths doc))) xs)))
(defn header-width [doc runtime dimension coordinates square]
  (let [coordinates (distinct (concat coordinates
                                      (keep (fn [key] (let [[d c] (e/key-coord key)] (when (= dimension d) c))) (keys (:hyperplanes doc)))
                                      (keep (fn [key] (let [target (e/key-coord key)]
                                                       (when (and (e/hyperplane? target) (= dimension (first (:hyperplane target))))
                                                         (second (:hyperplane target))))) (keys (:cell-widths doc)))))]
    (apply max square (map #(requested-width doc runtime {:hyperplane [dimension %]} true square) coordinates))))
