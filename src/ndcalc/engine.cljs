(ns ndcalc.engine
  (:require [clojure.string :as str]))

(def max-block-size 10000)

(defn fail [message] (throw (js/Error. message)))
(defn safe-integer? [x] (and (number? x) (js/Number.isSafeInteger x)))

(defn normalize-coord
  "Pad omitted dimensions with zero. Extra zero dimensions are harmless aliases."
  [dimensions coord]
  (when-not (and (safe-integer? dimensions) (<= 0 dimensions))
    (fail "Dimension count must be a non-negative integer."))
  (let [v (vec coord)]
    (if (and (= 1 (count v)) (string? (first v)))
      (do (when (str/blank? (first v)) (fail "Named cells need a non-empty name.")) v)
      (do
        (when-not (every? safe-integer? v) (fail "Coordinates must be safe integers."))
        (when (some #(not= 0 %) (drop dimensions v))
          (fail (str "Coordinate is outside this " dimensions "D table.")))
        (into (vec (take dimensions v)) (repeat (max 0 (- dimensions (count v))) 0))))))

(defn named? [coord] (and (= 1 (count coord)) (string? (first coord))))
(defn coord-key [coord] (js/JSON.stringify (clj->js coord)))
(defn key-coord [key] (vec (js->clj (js/JSON.parse key))))
(defn coord-label [coord] (if (named? coord) (str "$\"" (first coord) "\"") (coord-key coord)))

(defn cell-at [doc coord]
  (let [c (normalize-coord (:dimensions doc) coord)]
    (if (named? c) (get (:named doc) (first c)) (get (:cells doc) (coord-key c)))))

(defn put-cell [doc coord cell]
  (let [c (normalize-coord (:dimensions doc) coord)
        path (if (named? c) [:named (first c)] [:cells (coord-key c)])]
    (if cell (assoc-in doc path cell) (update-in doc (butlast path) dissoc (last path)))))

(defn switch-dimension [[x y] d]
  (cond (= d x) [y x]
        (= d y) [y 0]
        :else [y d]))

(defn initial-mapping [n] [(if (pos? n) 1 0) (if (> n 1) 2 0)])
(defn axis-value [coord dimension] (if (zero? dimension) 0 (nth coord (dec dimension))))
(defn set-axis [coord dimension value]
  (if (zero? dimension) coord (assoc coord (dec dimension) value)))
(defn plane-coord [origin [x y] a b] (-> origin (set-axis x a) (set-axis y b)))

(defn active-bounds [doc]
  (let [coords (map key-coord (keys (:cells doc)))]
    (when (seq coords)
      {:start (mapv #(apply min %) (apply map vector coords))
       :end (mapv #(apply max %) (apply map vector coords))})))

(defn active? [bounds coord]
  (or (named? coord)
      (and bounds (every? true? (map <= (:start bounds) coord (:end bounds))))))

(defn in-block? [origin current mapping coord]
  (let [[x y] mapping
        dims (set (remove zero? mapping))]
    (and (not (named? coord))
         (every? (fn [i]
                   (if (contains? dims (inc i))
                     (<= (min (nth origin i) (nth current i)) (nth coord i)
                         (max (nth origin i) (nth current i)))
                     (= (nth origin i) (nth coord i))))
                 (range (count coord))))))

(defn block-coords [origin current [x y :as mapping]]
  (let [a (axis-value origin x) b (axis-value current x)
        c (axis-value origin y) d (axis-value current y)
        width (inc (abs (- a b))) height (inc (abs (- c d)))]
    (when (> (* width height) max-block-size)
      (fail (str "Selections are limited to " max-block-size " cells.")))
    (vec (for [j (range (min c d) (inc (max c d)))
               i (range (min a b) (inc (max a b)))]
           (plane-coord origin mapping i j)))))

(defn compile-expression [source read-cell]
  (when (str/blank? source) (fail "Enter a JavaScript expression."))
  ((js/Function. "$" (str "\"use strict\"; return (" source "\n);")) read-cell))

(defn validate-cell! [cell]
  ;; Parsing only: do not execute user code during validation.
  (when-not (#{"value" "formula"} (:kind cell)) (fail "Choose value or formula explicitly."))
  (when-not (string? (:source cell)) (fail "A cell must have JavaScript source."))
  (when (str/blank? (:source cell)) (fail "Enter a JavaScript expression."))
  (js/Function. "$" (str "\"use strict\"; return (" (:source cell) "\n);"))
  cell)

(defn stringify [v]
  (try
    (cond
      (undefined? v) "undefined"
      (nil? v) "null"
      (string? v) v
      (fn? v) (.toString v)
      (= "bigint" (js* "typeof ~{}" v)) (str (.toString v) "n")
      (or (number? v) (boolean? v) (= "symbol" (js* "typeof ~{}" v))) (js/String v)
      :else (or (js/JSON.stringify
                  v (fn [_ value]
                      (cond
                        (= "bigint" (js* "typeof ~{}" value)) (str (.toString value) "n")
                        (fn? value) (.toString value)
                        (instance? js/Map value) (js/Array.from (.entries value))
                        (instance? js/Set value) (js/Array.from (.values value))
                        :else value)))
                (js/String v)))
    (catch :default _
      (try (js/String v) (catch :default _ "[unprintable value]")))))

(defn make-runtime
  "One memoized evaluation graph per content revision. Dependencies are dynamic;
   every edit invalidates the graph so conditional reads cannot become stale."
  [doc]
  (let [cache (atom {}) stack (atom []) dependencies (atom {}) calls (atom 0)
        rules (mapv (fn [rule]
                      (if-not (:enabled rule) rule
                        (try (assoc rule
                                    :coord-fn (compile-expression (:coord rule) nil)
                                    :value-fn (compile-expression (:value rule) nil))
                             (catch :default e (assoc rule :error (.-message e))))))
                    (:rules doc))]
    (letfn [(read-value [coord]
              (let [coord (normalize-coord (:dimensions doc) coord)
                    key (coord-key coord)]
                (when-let [parent (peek @stack)]
                  (swap! dependencies update parent (fnil conj #{}) key))
                (when (some #{key} @stack)
                  (fail (str "Circular reference: " (str/join " → " (conj @stack key)))))
                (when (> (count @stack) 256) (fail "Formula dependency depth exceeds 256."))
                (let [result
                      (if (contains? @cache key) (get @cache key)
                        (do
                          (when (> (swap! calls inc) 20000) (fail "Evaluation budget exceeded."))
                          (swap! stack conj key)
                          (let [result
                                (try
                                  (if-let [cell (cell-at doc coord)]
                                    (let [value (compile-expression (:source cell) (fn [& args] (read-value args)))]
                                      (if (= "formula" (:kind cell))
                                        (do (when-not (fn? value) (fail "Formula source must evaluate to a function."))
                                            {:value (.apply value nil (clj->js coord))})
                                        {:value value}))
                                    {:value js/undefined})
                                  (catch :default e {:error (or (.-message e) (str e))})
                                  (finally (swap! stack pop)))]
                            (swap! cache assoc key result)
                            result)))]
                  (if-let [error (:error result)] (fail error) (:value result)))))
            (evaluate [coord]
              (try {:value (read-value coord)}
                   (catch :default e {:error (.-message e)})))
            (format-cell [coord result]
              (reduce
                (fn [acc rule]
                  (if-not (:enabled rule) acc
                    (try
                      (when-let [error (:error rule)] (fail error))
                      (when-not (and (fn? (:coord-fn rule)) (fn? (:value-fn rule)))
                        (fail "Both formatting predicates must be functions."))
                      (if (.apply (:coord-fn rule) nil (clj->js coord))
                        ;; Value predicates are never run when the coordinate doesn't match.
                        (if (:error result) acc
                          (let [style ((:value-fn rule) (:value result))]
                            (cond
                              (js/Array.isArray style)
                              (do (when-not (every? string? (array-seq style))
                                    (fail "Class names must be strings."))
                                  (update acc :classes into (array-seq style)))
                              (string? style) (update acc :style str style ";")
                              :else (fail "Formatting must return an array of classes or a CSS style string."))))
                        acc)
                      (catch :default e
                        (update acc :errors conj (str (:name rule) ": " (.-message e)))))))
                {:classes [] :style "" :errors []} rules))]
      {:evaluate evaluate :format format-cell :dependencies dependencies :rules rules :cache cache})))

(defn resize-dimensions [doc n]
  (when-not (and (safe-integer? n) (<= 0 n 32)) (fail "Choose 0–32 dimensions."))
  (when (some (fn [key] (some #(not= 0 %) (drop n (key-coord key)))) (keys (:cells doc)))
    (fail "Cannot remove dimensions with non-zero populated coordinates. Clear those cells first."))
  (assoc doc :dimensions n
         :cells (into {} (map (fn [[key cell]] [(coord-key (normalize-coord n (key-coord key))) cell]) (:cells doc)))
         :view {:coord (normalize-coord n (take n (get-in doc [:view :coord])))
                :mapping (initial-mapping n)}))

(defn document->json [doc]
  ;; Null-prototype dictionaries preserve legal names such as "__proto__".
  (let [payload (clj->js (assoc (dissoc doc :cells :named) :format "ndcalc" :version 1))
        cells (js/Object.create nil) named (js/Object.create nil)]
    (doseq [[key cell] (:cells doc)] (aset cells key (clj->js cell)))
    (doseq [[name cell] (:named doc)] (aset named name (clj->js cell)))
    (aset payload "cells" cells)
    (aset payload "named" named)
    (js/JSON.stringify payload nil 2)))

(defn js-dictionary->map [object]
  ;; js->clj cannot recognize an object with its own "constructor" property.
  (when (and (some? object) (= "object" (js* "typeof ~{}" object))
             (not (js/Array.isArray object)))
    (into {} (map (fn [key] [key (js->clj (aget object key))]) (js/Object.keys object)))))

(defn json->document [text]
  (let [doc (js->clj (js/JSON.parse text) :keywordize-keys true)
        ;; Cell coordinate keys and named-cell names must remain strings, not keywords.
        raw (js/JSON.parse text)
        cells (js-dictionary->map (.-cells raw)) named (js-dictionary->map (.-named raw))
        n (:dimensions doc)]
    (when-not (and (= "ndcalc" (:format doc)) (= 1 (:version doc)))
      (fail "Not an ndcalc v1 document."))
    (when-not (and (safe-integer? n) (<= 0 n 32)) (fail "Invalid dimension count (0–32)."))
    (when-not (and (map? cells) (map? named) (vector? (:rules doc))
                   (string? (:css doc)) (string? (:title doc)))
      (fail "Invalid document structure."))
    (let [convert (fn [cell] (validate-cell! {:kind (get cell "kind") :source (get cell "source")}))
          normalized (reduce (fn [acc [key cell]]
                               (let [coord (normalize-coord n (key-coord key)) k (coord-key coord)]
                                 (when (named? coord) (fail "Named cells belong in the named section."))
                                 (when (contains? acc k) (fail "Duplicate coordinate aliases in import."))
                                 (assoc acc k (convert cell)))) {} cells)
          names (into {} (map (fn [[name cell]]
                               (normalize-coord n [name]) [name (convert cell)]) named))
          rules (mapv (fn [rule]
                        (when-not (and (string? (:coord rule)) (string? (:value rule))
                                       (string? (:name rule)) (boolean? (:enabled rule)))
                          (fail "Invalid conditional formatting rule."))
                        (validate-cell! {:kind "value" :source (:coord rule)})
                        (validate-cell! {:kind "value" :source (:value rule)})
                        (assoc rule :id (str (random-uuid)))) (:rules doc))
          view (:view doc)
          coord (normalize-coord n (or (:coord view) []))
          mapping (or (:mapping view) (initial-mapping n))]
      (when (or (named? coord) (not= 2 (count mapping))
                (not (every? #(and (safe-integer? %) (<= 0 % n)) mapping))
                (and (pos? (first mapping)) (= (first mapping) (second mapping))))
        (fail "Invalid saved view."))
      {:id (str (random-uuid)) :title (:title doc) :dimensions n
       :cells normalized :named names :rules rules :css (:css doc)
       :createdAt (.now js/Date) :updatedAt (.now js/Date)
       :view {:coord coord :mapping mapping}})))
