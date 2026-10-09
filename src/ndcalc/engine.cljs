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
  (if (map? coord)
    (let [[d c] (:hyperplane coord)]
      (when-not (and (= #{:hyperplane} (set (keys coord))) (vector? (:hyperplane coord))
                     (= 2 (count (:hyperplane coord))) (safe-integer? d) (<= 0 d dimensions)
                     (safe-integer? c) (or (pos? d) (zero? c)))
        (fail "Invalid hyperplane coordinate."))
      coord)
    (let [v (vec coord)]
      (if (and (= 1 (count v)) (string? (first v)))
        (do (when (str/blank? (first v)) (fail "Named cells need a non-empty name.")) v)
        (do
          (when-not (every? safe-integer? v) (fail "Coordinates must be safe integers."))
          (when (some #(not= 0 %) (drop dimensions v))
            (fail (str "Coordinate is outside this " dimensions "D table.")))
          (into (vec (take dimensions v)) (repeat (max 0 (- dimensions (count v))) 0)))))))

(defn named? [coord] (and (sequential? coord) (= 1 (count coord)) (string? (first coord))))
(defn hyperplane? [coord] (and (map? coord) (contains? coord :hyperplane)))
(defn coord-key [coord] (js/JSON.stringify (clj->js coord)))
(defn key-coord [key] (js->clj (js/JSON.parse key) :keywordize-keys true))
(defn coord-label [coord]
  (cond (named? coord) (str "$\"" (first coord) "\"")
        (hyperplane? coord) (str "$$(" (str/join "," (:hyperplane coord)) ")")
        :else (coord-key coord)))

(defn resolve-dimension [doc dimension]
  (let [d (if (string? dimension)
            (first (keep (fn [[d alias]] (when (= alias dimension) d)) (:aliases doc))) dimension)]
    (when-not (and (safe-integer? d) (<= 0 d (:dimensions doc)))
      (fail (str "Unknown axis: " dimension)))
    d))
(defn axis-label [doc d] (or (get (:aliases doc) d) (if (zero? d) "∅ null" (str "D" d))))
(defn hyperplane-coord [doc dimension coordinate]
  (normalize-coord (:dimensions doc) {:hyperplane [(resolve-dimension doc dimension) coordinate]}))
(defn target-label [doc coord]
  (if (hyperplane? coord)
    (let [[d c] (:hyperplane coord)] (str (axis-label doc d) "(" c ")")) (coord-label coord)))
(defn validate-aliases! [doc aliases]
  (when-not (and (map? aliases)
                (every? (fn [[d alias]] (and (safe-integer? d) (<= 1 d (:dimensions doc))
                                           (string? alias) (not (str/blank? alias)) (<= (count alias) 80))) aliases)
                (= (count aliases) (count (set (vals aliases)))))
    (fail "Axis aliases must be unique, non-empty names (up to 80 characters)."))
  aliases)
(defn set-aliases [doc aliases] (assoc doc :aliases (validate-aliases! doc aliases)))
(defn full-axis-queue [doc]
  (let [leading (or (seq (get-in doc [:view :axes])) (get-in doc [:view :mapping]))
        queue (vec (distinct (filter #(and (safe-integer? %) (<= 0 % (:dimensions doc)))
                                     (concat leading (get-in doc [:view :axis-order]) (get-in doc [:view :expelled])
                                             (range 1 (inc (:dimensions doc))) [0]))))]
    ;; Plane supports two null slots; volumes use the distinct prefix. Preserve
    ;; this explicit degenerate plane without dropping any actual dimension.
    (if (and (pos? (:dimensions doc)) (= [0 0] (vec (take 2 leading))))
      (into [0] queue) queue)))

(defn cell-at [doc coord]
  (let [c (normalize-coord (:dimensions doc) coord)]
    (cond (hyperplane? c) (get (:hyperplanes doc) (coord-key (:hyperplane c)))
          (named? c) (get (:named doc) (first c)) :else (get (:cells doc) (coord-key c)))))

(defn put-cell [doc coord cell]
  (let [c (normalize-coord (:dimensions doc) coord)
        path (cond (hyperplane? c) [:hyperplanes (coord-key (:hyperplane c))]
                   (named? c) [:named (first c)] :else [:cells (coord-key c)])]
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
  (or (named? coord) (hyperplane? coord)
      (and bounds (every? true? (map <= (:start bounds) coord (:end bounds))))))

(defn block-shape [origin current]
  (if (and (hyperplane? origin) (hyperplane? current) (= (first (:hyperplane origin)) (first (:hyperplane current))))
    [(inc (abs (- (second (:hyperplane origin)) (second (:hyperplane current)))))]
    (do
      (when-not (and (= (count origin) (count current))
                     (every? safe-integer? origin) (every? safe-integer? current))
        (fail "Selection corners must have matching numeric coordinates."))
      (mapv #(inc (abs (- %1 %2))) origin current))))

(defn block-size [origin current] (reduce * 1 (block-shape origin current)))

(defn in-block? [origin current coord]
  (if (every? hyperplane? [origin current coord])
    (let [[d a] (:hyperplane origin) [e b] (:hyperplane current) [f c] (:hyperplane coord)]
      (and (= d e f) (<= (min a b) c (max a b))))
    (and (not (named? origin)) (not (named? current)) (not (named? coord))
         (not-any? hyperplane? [origin current coord])
         (= (count origin) (count current) (count coord))
         (every? true? (map #(<= (min %1 %2) %3 (max %1 %2)) origin current coord)))))

(defn block-coords
  "Enumerate the inclusive n-dimensional box; the first dimension varies fastest."
  [origin current]
  (when (> (block-size origin current) max-block-size)
    (fail (str "Selections are limited to " max-block-size " cells.")))
  (if (hyperplane? origin)
    (let [[d a] (:hyperplane origin) [_ b] (:hyperplane current)]
      (mapv #(hash-map :hyperplane [d %]) (range (min a b) (inc (max a b)))))
    (reduce (fn [coords [a b]]
              (vec (for [value (range (min a b) (inc (max a b))) prefix coords]
                     (conj prefix value))))
            [[]] (map vector origin current))))

(defn expression-source [source]
  ;; Persist the original shorthand, not its expansion.
  (str/replace source #"^\s*=>" "() =>"))
(defn expression-factory [source]
  (when (str/blank? source) (fail "Enter a JavaScript expression."))
  (js/Function. "$" "$$" "_" (str "\"use strict\"; return (" (expression-source source) "\n);")))
(defn compile-expression
  ([source read-cell] (compile-expression source read-cell nil nil))
  ([source read-cell read-hyperplane current] ((expression-factory source) read-cell read-hyperplane current)))
(defonce coordinate-brand (js/Symbol "ndcalc.coordinate"))
(defn coordinate-object? [v]
  (and (some? v) (= "object" (js* "typeof ~{}" v)) (some? (aget v coordinate-brand))))
(defn coordinate-target [v] (aget v coordinate-brand))

(defn validate-cell! [cell]
  ;; Parsing only: do not execute user code during validation.
  (when-not (#{"value" "formula"} (:kind cell)) (fail "Choose value or formula explicitly."))
  (when-not (string? (:source cell)) (fail "A cell must have JavaScript source."))
  (when (str/blank? (:source cell)) (fail "Enter a JavaScript expression."))
  (expression-factory (:source cell))
  cell)

(defn literal-string [cell]
  ;; Recognize literals syntactically before evaluating. Never evaluate an
  ;; arbitrary expression to decide how to present it in the editor.
  (when (and (= "value" (:kind cell)) (string? (:source cell)))
    (let [source (str/trim (:source cell))]
      (when (re-matches #"(?:\"(?:[^\"\\]|\\[\s\S])*\"|'(?:[^'\\]|\\[\s\S])*'|`(?:[^`\\$]|\\[\s\S]|\$(?!\{))*`)" source)
        (try {:text ((js/Function. (str "\"use strict\"; return (" source "\n);")))}
             (catch :default _ nil))))))

(defn cell-width [doc coord] (get (:cell-widths doc) (coord-key (normalize-coord (:dimensions doc) coord))))
(defn set-cell-width [doc coord width]
  (when-not (or (nil? width) (and (safe-integer? width) (<= 35 width 2000)))
    (fail "Cell width must be 35–2000 pixels, or Auto."))
  (let [key (coord-key (normalize-coord (:dimensions doc) coord))]
    (if width (assoc-in doc [:cell-widths key] width) (update doc :cell-widths dissoc key))))

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
        bounds (active-bounds doc)
        rules (mapv (fn [rule]
                      (if-not (:enabled rule) rule
                        (try (assoc rule :coord-factory (expression-factory (:coord rule))
                                         :value-factory (expression-factory (:value rule)))
                             (catch :default err (assoc rule :error (.-message err)))))) (:rules doc))]
    (letfn [(position [target dimension]
              (cond (zero? dimension) 0
                    (hyperplane? target) (let [[d c] (:hyperplane target)]
                                          (if (= d dimension) c (fail "A hyperplane has no fixed coordinate on another axis.")))
                    (named? target) (fail "Named cells have no axis coordinates.")
                    :else (axis-value target dimension)))
            (coordinate-object [target]
              (let [object (js/Object.create nil)]
                (aset object coordinate-brand target)
                (aset object "kind" (cond (hyperplane? target) "hyperplane" (named? target) "named" :else "cell"))
                (when (hyperplane? target) (aset object "dimension" (first (:hyperplane target))))
                (let [coordinates (clj->js target)]
                  (when (hyperplane? target) (js/Object.freeze (aget coordinates "hyperplane")))
                  (aset object "coords" (js/Object.freeze coordinates)))
                (aset object "coordinate" (fn [dimension] (position target (resolve-dimension doc dimension))))
                (aset object "offset" (fn [dimension delta]
                                        (when-not (safe-integer? delta) (fail "Offsets must be safe integers."))
                                        (let [d (resolve-dimension doc dimension)
                                              c (+ (position target d) (if (zero? d) 0 delta))
                                              next (cond (zero? d) target
                                                         (hyperplane? target) (hyperplane-coord doc d c)
                                                         :else (set-axis target d c))]
                                          (coordinate-object (normalize-coord (:dimensions doc) next)))))
                (aset object "value" (fn [& dimensions]
                                       (when (> (count dimensions) 1) (fail "value() accepts zero or one axis."))
                                       (read-value (if (seq dimensions)
                                                     (let [d (resolve-dimension doc (first dimensions))]
                                                       (hyperplane-coord doc d (position target d))) target))))
                (aset object "toJSON" (fn [] (clj->js target)))
                (js/Object.freeze object)))
            (context [target]
              (let [current (coordinate-object target)
                    dollar (fn [& args]
                             (let [one (first args)]
                               (read-value (cond (and (= 1 (count args)) (coordinate-object? one)) (coordinate-target one)
                                                 (and (= 1 (count args)) (js/Array.isArray one)) (vec (array-seq one))
                                                 :else args))))
                    hyper (fn [dimension & coordinates]
                            (when (> (count coordinates) 1) (fail "$$ accepts an axis and one coordinate."))
                            (let [d (resolve-dimension doc dimension)
                                  c (if (seq coordinates) (first coordinates) current)
                                  c (if (coordinate-object? c) (position (coordinate-target c) d) c)]
                              (read-value (hyperplane-coord doc d c))))
                    ;; An arrow target has no non-configurable caller/prototype
                    ;; properties, so aliases such as 'name' and '__proto__' work.
                    callable (js* "((f) => (...args) => f(...args))(~{})" hyper)
                    proxy (js/Proxy. callable
                                    #js {:get (fn [target property receiver]
                                                (if (and (string? property)
                                                         (some #{property} (vals (:aliases doc))))
                                                  (hyper property current)
                                                  (js/Reflect.get target property receiver)))})]
                #js [dollar proxy current]))
            (read-value [coord]
              (let [coord (normalize-coord (:dimensions doc) coord)
                    key (coord-key coord) cell (cell-at doc coord)]
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
                                  (if cell
                                    (let [value (.apply (expression-factory (:source cell)) nil (context coord))]
                                      (if (= "formula" (:kind cell))
                                        (do (when-not (fn? value) (fail "Formula source must evaluate to a function."))
                                            {:value (.apply value nil (clj->js (if (hyperplane? coord) (:hyperplane coord) coord)))})
                                        {:value value}))
                                    {:value js/undefined})
                                  (catch :default e {:error (or (.-message e) (str e))})
                                  (finally (swap! stack pop)))]
                            ;; Missing coordinates are constant and need no cache entry.
                            (when cell (swap! cache assoc key result))
                            result)))]
                  (if-let [error (:error result)] (fail error) (:value result)))))
            (evaluate [coord]
              ;; Limit one dependency expansion, not a lifetime of browsing windows.
              (reset! calls 0)
              (try {:value (read-value coord)}
                   (catch :default e {:error (.-message e)})))
            (format-cell [coord result]
              (reset! calls 0)
              (let [coord (normalize-coord (:dimensions doc) coord)
                    empty-format {:classes [] :style "" :errors []}]
                ;; Named cells remain eligible; numeric cells use the full hypercube.
                (if-not (active? bounds coord) empty-format
                 (reduce
                (fn [acc rule]
                  (if-not (:enabled rule) acc
                    (try
                      (when-let [error (:error rule)] (fail error))
                      (let [ctx (context coord) predicate (.apply (:coord-factory rule) nil ctx)]
                        (when-not (fn? predicate) (fail "Coordinate predicate must be a function."))
                        (if (.apply predicate nil (if (hyperplane? coord) #js [(aget ctx 2)] (clj->js coord)))
                        ;; Value predicates are never constructed/run when coordinates don't match.
                        (if (:error result) acc
                          (let [value-fn (.apply (:value-factory rule) nil ctx)
                                _ (when-not (fn? value-fn) (fail "Value predicate must be a function."))
                                style (value-fn (:value result))]
                            (cond
                              (js/Array.isArray style)
                              (do (when-not (every? string? (array-seq style))
                                    (fail "Class names must be strings."))
                                  (update acc :classes into (array-seq style)))
                              (string? style) (update acc :style str style ";")
                              :else (fail "Formatting must return an array of classes or a CSS style string."))))
                        acc))
                      (catch :default e
                        (update acc :errors conj (str (:name rule) ": " (.-message e)))))))
                empty-format rules))))]
      {:doc doc :evaluate evaluate :format format-cell :dependencies dependencies :rules rules :cache cache})))

(defn resize-dimensions [doc n]
  (when-not (and (safe-integer? n) (<= 0 n 32)) (fail "Choose 0–32 dimensions."))
  (when (some (fn [key] (some #(not= 0 %) (drop n (key-coord key)))) (keys (:cells doc)))
    (fail "Cannot remove dimensions with non-zero populated coordinates. Clear those cells first."))
  (when (some #(> (first (key-coord %)) n) (keys (:hyperplanes doc)))
    (fail "Cannot remove dimensions with populated hyperplane cells. Clear their headers first."))
  (let [doc (assoc doc :dimensions n
                  :aliases (into {} (filter #(<= (key %) n) (:aliases doc)))
                  :cells (into {} (map (fn [[key cell]] [(coord-key (normalize-coord n (key-coord key))) cell]) (:cells doc))))
        widths (into {} (keep (fn [[key width]]
                                (try [(coord-key (normalize-coord n (key-coord key))) width]
                                     (catch :default _ nil))) (:cell-widths doc)))
        queue (full-axis-queue doc)]
    (assoc doc :cell-widths widths :view {:coord (normalize-coord n (take n (get-in doc [:view :coord])))
                     :axes queue :mapping (vec (take 2 (concat queue [0])))
                     :axis-order (vec (filter #(<= % n) (get-in doc [:view :axis-order])))
                     :expelled (vec (filter #(<= % n) (get-in doc [:view :expelled])))})))

(defn document->json [doc]
  ;; Null-prototype dictionaries preserve legal names such as "__proto__".
  (let [payload (clj->js (assoc (dissoc doc :cells :named :hyperplanes) :format "ndcalc" :version 1))
        cells (js/Object.create nil) named (js/Object.create nil) hyperplanes (js/Object.create nil)]
    (doseq [[key cell] (:cells doc)] (aset cells key (clj->js cell)))
    (doseq [[name cell] (:named doc)] (aset named name (clj->js cell)))
    (doseq [[key cell] (:hyperplanes doc)] (aset hyperplanes key (clj->js cell)))
    (aset payload "cells" cells)
    (aset payload "named" named)
    (aset payload "hyperplanes" hyperplanes)
    (js/JSON.stringify payload nil 2)))

(defn js-dictionary->map [object]
  ;; js->clj cannot recognize an object with its own "constructor" property.
  (when (and (some? object) (= "object" (js* "typeof ~{}" object))
             (not (js/Array.isArray object)))
    (into {} (map (fn [key] [key (js->clj (aget object key))]) (js/Object.keys object)))))

(defn decode-aliases [object]
  (let [aliases (if (some? object) (js-dictionary->map object) {})]
    (when-not (map? aliases) (fail "Invalid axis aliases."))
    (into {} (map (fn [[key alias]]
                    (let [d (js/Number key)]
                      (when-not (and (safe-integer? d) (= key (str d))) (fail "Invalid alias dimension."))
                      [d alias])) aliases))))

(defn json->document [text]
  (let [doc (js->clj (js/JSON.parse text) :keywordize-keys true)
        ;; Cell coordinate keys and named-cell names must remain strings, not keywords.
        raw (js/JSON.parse text)
        cells (js-dictionary->map (.-cells raw)) named (js-dictionary->map (.-named raw))
        hyperplanes (if (some? (.-hyperplanes raw)) (js-dictionary->map (.-hyperplanes raw)) {})
        n (:dimensions doc) aliases (decode-aliases (.-aliases raw))]
    (when-not (and (= "ndcalc" (:format doc)) (= 1 (:version doc)))
      (fail "Not an ndcalc v1 document."))
    (when-not (and (safe-integer? n) (<= 0 n 32)) (fail "Invalid dimension count (0–32)."))
    (validate-aliases! doc aliases)
    (when-not (and (map? cells) (map? named) (map? hyperplanes) (vector? (:rules doc))
                   (string? (:css doc)) (string? (:title doc)))
      (fail "Invalid document structure."))
    (let [convert (fn [cell] (validate-cell! {:kind (get cell "kind") :source (get cell "source")}))
          normalized (reduce (fn [acc [key cell]]
                               (let [coord (normalize-coord n (key-coord key)) k (coord-key coord)]
                                 (when (or (named? coord) (hyperplane? coord))
                                   (fail "Only numeric coordinate arrays belong in cells."))
                                 (when (contains? acc k) (fail "Duplicate coordinate aliases in import."))
                                 (assoc acc k (convert cell)))) {} cells)
          headers (reduce (fn [acc [key cell]]
                            (let [coord (normalize-coord n {:hyperplane (key-coord key)})
                                  k (coord-key (:hyperplane coord))]
                              (when (contains? acc k) (fail "Duplicate hyperplane aliases in import."))
                              (assoc acc k (convert cell)))) {} hyperplanes)
          names (into {} (map (fn [[name cell]]
                               (normalize-coord n [name]) [name (convert cell)]) named))
          rules (mapv (fn [rule]
                        (when-not (and (string? (:coord rule)) (string? (:value rule))
                                       (string? (:name rule)) (boolean? (:enabled rule)))
                          (fail "Invalid conditional formatting rule."))
                        (validate-cell! {:kind "value" :source (:coord rule)})
                        (validate-cell! {:kind "value" :source (:value rule)})
                        (assoc rule :id (str (random-uuid)))) (:rules doc))
          widths (if (some? (aget raw "cell-widths")) (js-dictionary->map (aget raw "cell-widths")) {})
          _ (when-not (map? widths) (fail "Invalid cell widths."))
          widths (reduce (fn [acc [key width]]
                           (let [coord (normalize-coord n (key-coord key)) canonical (coord-key coord)]
                             (when-not (and (safe-integer? width) (<= 35 width 2000)) (fail "Invalid cell width."))
                             (when (contains? acc canonical) (fail "Duplicate cell-width coordinate aliases."))
                             (:cell-widths (set-cell-width {:dimensions n :cell-widths acc} coord width)))) {} widths)
          view (:view doc)
          coord (normalize-coord n (or (:coord view) []))
          mapping (or (:mapping view) (initial-mapping n))
          expelled (or (:expelled view) [])
          axis-order (or (:axis-order view) [])
          axes (:axes view)]
      (when (or (named? coord) (hyperplane? coord) (not (vector? mapping)) (not= 2 (count mapping))
                (not (every? #(and (safe-integer? %) (<= 0 % n)) mapping))
                (and (pos? (first mapping)) (= (first mapping) (second mapping))))
        (fail "Invalid saved view."))
      (when-not (and (vector? expelled) (= (count expelled) (count (set expelled)))
                      (every? #(and (safe-integer? %) (<= 1 % n)) expelled))
        (fail "Invalid expelled dimensions."))
      (when-not (and (vector? axis-order) (= (count axis-order) (count (set axis-order)))
                    (every? #(and (safe-integer? %) (<= 0 % n)) axis-order))
        (fail "Invalid axis recency order."))
      (when (and (some? axes)
                 (not (and (vector? axes)
                           (= (count (remove zero? axes)) (count (set (remove zero? axes))))
                           (or (<= (count (filter zero? axes)) 1)
                               (and (= 2 (count (filter zero? axes))) (= [0 0] (vec (take 2 axes)))))
                           (every? #(and (safe-integer? %) (<= 0 % n)) axes))))
        (fail "Invalid full axis queue."))
      (let [queue (full-axis-queue (assoc doc :view (assoc view :mapping mapping)))]
        {:id (str (random-uuid)) :title (:title doc) :dimensions n
         :cells normalized :named names :hyperplanes headers :aliases aliases :cell-widths widths :rules rules :css (:css doc)
         :createdAt (.now js/Date) :updatedAt (.now js/Date)
         :view {:coord coord :mapping (vec (take 2 (concat queue [0]))) :axes queue
                :expelled expelled :axis-order axis-order}}))))
