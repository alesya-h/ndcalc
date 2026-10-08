(ns ndcalc.engine-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [ndcalc.engine :as e]
            [ndcalc.demo :as demo]))

(defn value [source] {:kind "value" :source source})
(defn formula [source] {:kind "formula" :source source})
(defn result [doc coord] ((:evaluate (e/make-runtime doc)) coord))

(deftest coordinates
  (is (= [] (e/normalize-coord 0 [])))
  (is (= [] (e/normalize-coord 0 [0 0 0])))
  (is (= [0 0 0 0 0] (e/normalize-coord 5 [])))
  (is (= [-3 4 0 0 0] (e/normalize-coord 5 [-3 4])))
  (is (= ["a"] (e/normalize-coord 0 ["a"])))
  (is (thrown? js/Error (e/normalize-coord 0 [1])))
  (is (thrown? js/Error (e/normalize-coord 2 [0 0 1])))
  (is (thrown? js/Error (e/normalize-coord 2 [0.5])))
  (is (thrown? js/Error (e/normalize-coord 2 ["name" 1])))
  (is (thrown? js/Error (e/normalize-coord 2 [js/NaN])))
  (is (thrown? js/Error (e/normalize-coord 2 [" "]))))

(deftest requested-dimension-sequence
  (is (= [[2 3] [3 1] [1 0] [0 1] [1 2] [2 1] [1 2]]
         (rest (reductions e/switch-dimension [1 2] [3 1 1 1 2 1 2]))))
  (is (= [0 0] (e/initial-mapping 0)))
  (is (= [1 0] (e/initial-mapping 1)))
  (is (= [1 2] (e/initial-mapping 5)))
  (is (= [2 0] (e/switch-dimension [1 2] 0))))

(deftest dimensions-zero-through-five
  (doseq [n (range 6)]
    (let [doc (-> (demo/blank-document "test" n)
                  (e/put-cell [] (value "42"))
                  (e/put-cell ["rank"] (formula (str "name => " n))))]
      (is (= 42 (:value (result doc (repeat n 0)))))
      (is (= n (:value (result doc ["rank"]))))
      (is (= (vec (repeat n 0)) (:start (e/active-bounds doc))))
      (is (= 1 (count (:cells doc)))))))

(deftest five-dimensional-formulas
  (let [doc (-> (demo/blank-document "5d" 5)
                (e/put-cell [-1 3 4 5 6] (value "10"))
                (e/put-cell [-1 2 4 5 6] (formula "(a,b,...rest) => $(a,b+1,...rest)+1")))
        runtime (e/make-runtime doc)]
    (is (= 11 (:value ((:evaluate runtime) [-1 2 4 5 6]))))
    (is (= #{"[-1,3,4,5,6]"} (get @(:dependencies runtime) "[-1,2,4,5,6]")))))

(deftest explicit-function-values
  (let [doc (-> (demo/blank-document "functions" 1)
                (e/put-cell [0] (value "x => x + 3"))
                (e/put-cell [1] (formula "x => $(0)(7)")))]
    (is (fn? (:value (result doc [0]))))
    (is (= 10 (:value (result doc [1]))))
    (is (str/includes? (e/stringify (:value (result doc [0]))) "x + 3"))))

(deftest values-are-arbitrary-javascript
  (doseq [[source expected] [["null" "null"] ["undefined" "undefined"] ["true" "true"]
                             ["123n" "123n"] ["Symbol('hi')" "Symbol(hi)"]
                             ["({answer:42})" "{\"answer\":42}"] ["new Set([1,2])" "[1,2]"]
                             ["new Map([['x',1]])" "[[\"x\",1]]"]]]
    (is (= expected (e/stringify (:value (result (e/put-cell (demo/blank-document "v" 0) [] (value source)) []))))))
  (is (= "[object Object]" (e/stringify (let [o #js {}] (set! (.-self o) o) o)))))

(deftest missing-and-named-cells
  (let [doc (-> (demo/blank-document "names" 2)
                (e/put-cell ["input"] (value "9"))
                (e/put-cell ["double"] (formula "name => $(\"input\") * 2"))
                (e/put-cell [] (formula "(x,y) => $(\"double\") + 1"))
                (e/put-cell [1] (formula "(x,y) => $(100,200) === undefined ? 7 : 0")))]
    (is (= 19 (:value (result doc []))))
    (is (= 7 (:value (result doc [1]))))
    (is (undefined? (:value (result doc [4 5]))))
    (is (= 23 (:value (result (e/put-cell doc ["input"] (value "11")) []))))))

(deftest errors-and-cycles
  (let [base (demo/blank-document "errors" 1)]
    (is (str/includes? (:error (result (e/put-cell base [0] (formula "x => $(0)")) [0])) "Circular"))
    (is (str/includes? (:error (result (-> base
                                          (e/put-cell [0] (formula "x => $(\"a\")"))
                                          (e/put-cell ["a"] (formula "name => $(0)"))) [0])) "Circular"))
    (is (str/includes? (:error (result (e/put-cell base [0] (formula "42")) [0])) "function"))
    (is (:error (result (e/put-cell base [0] (value "({")) [0])))
    (is (:error (result (-> base (e/put-cell [0] (formula "x => $(1) + 1"))
                              (e/put-cell [1] (formula "x => {throw new Error('broken')}"))) [0])))))

(deftest memoization
  (let [doc (-> (demo/blank-document "memo" 1)
                (e/put-cell ["counter"] (value "({n:0})"))
                (e/put-cell [0] (formula "x => ++$(\"counter\").n")))
        runtime (e/make-runtime doc)]
    (is (= 1 (:value ((:evaluate runtime) [0]))))
    (is (= 1 (:value ((:evaluate runtime) [0]))))))

(deftest preview-navigation-does-not-exhaust-the-evaluation-budget
  (let [runtime (e/make-runtime (demo/blank-document "browse" 1))]
    (is (every? #(not (:error ((:evaluate runtime) [%]))) (range 20002)))
    (is (empty? @(:cache runtime))))
  (let [doc (e/put-cell (demo/blank-document "budget" 1) [0]
                       (formula "x => {for(let i=1;i<20002;i++) $(i); return 0}"))]
    (is (re-find #"Evaluation budget" (:error (result doc [0]))))))

(deftest axis-recency-imports-are-validated
  (let [doc (assoc-in (demo/blank-document "history" 4) [:view :axis-order] [3 0 1 4 2])]
    (is (= [3 0 1 4 2] (get-in (e/json->document (e/document->json doc)) [:view :axis-order])))
    (is (= [0 1 2] (get-in (e/resize-dimensions doc 2) [:view :axis-order])))
    (doseq [bad [[1 1] [-1] [5] [1.5] ["1"] {:axis 1}]]
      (is (thrown? js/Error (e/json->document (e/document->json (assoc-in doc [:view :axis-order] bad))))))))

(deftest color-cube-example
  (let [doc (demo/color-document) runtime (e/make-runtime doc)
        result ((:evaluate runtime) [5 4 3])]
    (is (= 4 (:dimensions doc)))
    (is (= 1024 (count (:cells doc))))
    (is (= {:start [0 0 0 0] :end [7 7 7 1]} (e/active-bounds doc)))
    (is (= [5 4 3 0] (js->clj (:value result))))
    (is (re-find #"lch\(62.5% 75 135\)" (:style ((:format runtime) [5 4 3 1] ((:evaluate runtime) [5 4 3 1])))))
    (is (re-find #"oklch\(62.5% 0.2 135\)" (:style ((:format runtime) [5 4 3] result))))
    (is (empty? (:style ((:format runtime) [8 4 3] {:value #js [8 4 3]}))))
    (is (= (:cells doc) (:cells (e/json->document (e/document->json doc)))))))

(deftest formatting
  (let [doc (assoc (-> (demo/blank-document "style" 2)
                       (e/put-cell [0 1] (value "5"))
                       (e/put-cell ["input"] (value "5")))
                   :rules [{:name "static" :enabled true :coord "() => true" :value "v => ['base']"}
                           {:name "positive" :enabled true :coord "(x,y) => x === 0" :value "v => v > 0 ? 'color: green' : ''"}
                           {:name "later" :enabled true :coord "() => true" :value "v => 'color: red'"}
                           {:name "skip" :enabled true :coord "() => false" :value "v => { throw Error('must not run') }"}
                           {:name "disabled" :enabled false :coord "() => true" :value "not valid JavaScript"}
                           {:name "named" :enabled true :coord "name => name === 'input'" :value "v => ['named']"}])
        runtime (e/make-runtime doc)
        style ((:format runtime) [0 1] {:value 5})]
    (is (= ["base"] (:classes style)))
    (is (= "color: green;color: red;" (:style style)))
    (is (empty? (:errors style)))
    (is (= ["base" "named"] (:classes ((:format runtime) ["input"] {:value 5}))))))

(deftest formatting-errors-are-isolated
  (let [runtime (e/make-runtime (assoc (e/put-cell (demo/blank-document "bad" 0) [] (value "3"))
                                     :rules [{:name "invalid" :enabled true :coord "() => true" :value "v => 42"}
                                             {:name "fine" :enabled true :coord "() => true" :value "v => ['ok']"}]))
        fmt ((:format runtime) [] {:value 3})]
    (is (= ["ok"] (:classes fmt)))
    (is (= 1 (count (:errors fmt))))))

(deftest formatting-is-limited-to-the-active-hypercube
  (set! (.-ndcalcFormattingCalls js/globalThis) 0)
  (let [rule {:name "all" :enabled true
              :coord "(...coord) => {globalThis.ndcalcFormattingCalls++; return true;}"
              :value "v => ['inside']"}
        doc (-> (demo/blank-document "5D formatting" 5)
                (e/put-cell [-2 -3 -1 0 2] (value "1"))
                (e/put-cell [2 3 1 0 4] (value "2"))
                (assoc :rules [rule]))
        runtime (e/make-runtime doc)
        outside [[-3 0 0 0 3] [0 -4 0 0 3] [0 0 -2 0 3]
                 [0 0 0 1 3] [0 0 0 0 5] []]
        empty-format {:classes [] :style "" :errors []}]
    (doseq [coord outside]
      (is (= empty-format ((:format runtime) coord {:value 1}))))
    (is (= 0 (.-ndcalcFormattingCalls js/globalThis)))
    (is (= ["inside"] (:classes ((:format runtime) [0 0 0 0 3] {:value js/undefined}))))
    (is (= ["inside"] (:classes ((:format runtime) [-2 -3 -1 0 2] {:value 1}))))
    (is (= 2 (.-ndcalcFormattingCalls js/globalThis)))
    (let [empty-runtime (e/make-runtime (assoc (demo/blank-document "empty" 0) :rules [rule]))]
      (is (= empty-format ((:format empty-runtime) [] {:value js/undefined}))))
    (let [named-runtime (e/make-runtime (-> (demo/blank-document "named only" 0)
                                           (e/put-cell ["input"] (value "5"))
                                           (assoc :rules [rule])))]
      (is (= ["inside"] (:classes ((:format named-runtime) ["input"] {:value 5})))))))

(deftest formatting-bounds-update-after-content-edits
  (let [doc (-> (demo/blank-document "resize bounds" 2)
                (e/put-cell [] (value "1"))
                (assoc :rules [{:name "static" :enabled true :coord "() => true" :value "v => ['base']"}]))
        expanded (e/put-cell doc [2 2] (value "2"))]
    (is (empty? (:classes ((:format (e/make-runtime doc)) [1 1] {:value js/undefined}))))
    (is (= ["base"] (:classes ((:format (e/make-runtime expanded)) [1 1] {:value js/undefined}))))
    (is (empty? (:classes ((:format (e/make-runtime (e/put-cell expanded [2 2] nil))) [1 1] {:value js/undefined}))))))

(deftest active-area
  (let [doc (-> (demo/blank-document "bounds" 5)
                (e/put-cell [-5 3] (value "null"))
                (e/put-cell [7 -2 8 4 -9] (value "undefined"))
                (e/put-cell ["excluded"] (value "42")))
        bounds (e/active-bounds doc)]
    (is (= {:start [-5 -2 0 0 -9] :end [7 3 8 4 0]} bounds))
    (is (e/active? bounds [0 0 2 2 -1]))
    (is (not (e/active? bounds [0 4 2 2 -1])))
    (is (nil? (e/active-bounds (demo/blank-document "empty" 3))))
    (is (= {:start [-5 3 0 0 0] :end [-5 3 0 0 0]}
           (e/active-bounds (e/put-cell doc [7 -2 8 4 -9] nil))))))

(deftest selections
  (is (= [[-1 3 9] [0 3 9] [1 3 9] [-1 4 9] [0 4 9] [1 4 9]]
         (e/block-coords [1 4 9] [-1 3 9])))
  (is (= [[2 3 0] [2 3 1] [2 3 2]] (e/block-coords [2 3 0] [2 3 2])))
  (is (= [[]] (e/block-coords [] [])))
  (is (thrown? js/Error (e/block-coords [0 0] [100 100])))
  (is (not (e/in-block? [0 0 0] [2 2 0] [1 1 1]))))

(deftest hyperbox-selections
  (doseq [n (range 6)]
    (let [a (vec (repeat n -1)) b (vec (repeat n 0)) coords (e/block-coords b a)]
      (is (= (js/Math.pow 2 n) (e/block-size a b) (count coords)))
      (is (= (count coords) (count (set coords))))
      (is (= a (first coords)))
      (is (= b (last coords)))
      (is (every? #(e/in-block? a b %) coords))))
  (is (= [2 3 4] (e/block-shape [-1 2 8] [0 0 5])))
  (is (e/in-block? [0 0 0] [1 1 1] [0 1 1]))
  (is (not (e/in-block? [0 0 0] [1 1 1] [0 1 2])))
  (is (not (e/in-block? [0 0] [1 1] [0 1 0])))
  (is (not (e/in-block? [0] [1] ["named"])))
  (is (= 10000 (count (e/block-coords [0 0 0 0 0] [9 9 9 9 0]))))
  (is (thrown? js/Error (e/block-coords [0 0 0 0 0] [9 9 9 9 9])))
  (is (thrown? js/Error (e/block-coords [0 0] [0])))
  (is (thrown? js/Error (e/block-coords ["name"] ["name"]))))

(deftest resizing-does-not-lose-data
  (let [doc (-> (demo/blank-document "rank" 5) (e/put-cell [1 2 0 0 0] (value "3")))]
    (is (= {"[1,2]" (value "3")} (:cells (e/resize-dimensions doc 2))))
    (is (thrown? js/Error (e/resize-dimensions doc 1)))
    (is (= 6 (:dimensions (e/resize-dimensions doc 6))))))

(deftest json-round-trip
  (let [doc (-> (demo/blank-document "json" 5)
                (e/put-cell [-1 2 3 4 5] (value "({x:12n, fn:x=>x*2})"))
                (e/put-cell ["name:with.punctuation"] (formula "name => 9")))
        loaded (e/json->document (e/document->json doc))]
    (is (= (:cells doc) (:cells loaded)))
    (is (= (:named doc) (:named loaded)))
    (is (not= (:id doc) (:id loaded)))
    (is (= "{\"x\":\"12n\",\"fn\":\"x=>x*2\"}" (e/stringify (:value (result loaded [-1 2 3 4 5])))))
    (is (thrown? js/Error (e/json->document "{}")))
    (is (thrown? js/Error (e/json->document "{bad json")))))

(deftest reserved-names-round-trip
  (let [doc (-> (demo/blank-document "names" 0)
                (e/put-cell ["__proto__"] (value "42"))
                (e/put-cell ["constructor"] (value "7")))
        loaded (e/json->document (e/document->json doc))]
    (is (= (:named doc) (:named loaded)))
    (is (= 42 (:value (result loaded ["__proto__"]))))))

(deftest imports-validate-without-executing-code
  (set! (.-ndcalcImportCounter js/globalThis) 0)
  (let [source "(() => { globalThis.ndcalcImportCounter++; return 42; })()"
        doc (e/put-cell (demo/blank-document "trust" 0) [] (value source))
        loaded (e/json->document (e/document->json doc))]
    (is (= 0 (.-ndcalcImportCounter js/globalThis)))
    (is (= 42 (:value (result loaded []))))
    (is (= 1 (.-ndcalcImportCounter js/globalThis)))))

(deftest dynamic-dependencies-recalculate
  (let [doc (-> (demo/blank-document "branch" 1)
                (e/put-cell ["left"] (value "true"))
                (e/put-cell [1] (value "5"))
                (e/put-cell [2] (value "9"))
                (e/put-cell [0] (formula "x => $(\"left\") ? $(1) : $(2)")))]
    (is (= 5 (:value (result doc [0]))))
    (is (= 9 (:value (result (e/put-cell doc ["left"] (value "false")) [0]))))))

(deftest demo-is-live
  (let [doc (demo/demo-document)]
    (is (= 134 (:value (result doc [3 1]))))
    (is (= 600 (:value (result doc [1 7]))))
    (is (= 269 (:value (result (e/put-cell doc ["multiplier"] (value "2")) [3 1]))))
    (is (= "Dimension five" (:value (result doc [1 1 0 0 1]))))))
