(ns ndcalc.demo
  (:require [ndcalc.engine :as e]))

(def default-css
  ".heading {\n  color: var(--accent);\n  font-weight: 600;\n  background: var(--accent-soft);\n}\n.positive { color: var(--green); }\n.negative { color: var(--red); }\n.total {\n  background: var(--accent-soft);\n  font-weight: 600;\n}\n")

(defn blank-document [title dimensions]
  {:id (str (random-uuid)) :title title :dimensions dimensions
   :createdAt (.now js/Date) :updatedAt (.now js/Date)
   :cells {} :named {} :rules [] :css default-css
   :view {:coord (vec (repeat dimensions 0)) :mapping (e/initial-mapping dimensions)}})

(defn color-document []
  (let [doc (blank-document "OKLCH color cube" 3)
        doc (reduce (fn [d c] (e/put-cell d c {:kind "formula" :source "(a,b,c) => [a,b,c]"}))
                    doc (for [c (range 8) b (range 8) a (range 8)] [a b c]))]
    (assoc doc :view {:coord [0 0 3] :mapping [1 2]}
           :css ""
           :rules [{:id (str (random-uuid)) :name "OKLCH coordinates" :enabled true
                    :coord "(...coord) => true"
                    :value "v => Array.isArray(v) ? `background-color: oklch(${v[0]*100/8}% ${v[1]*0.4/8} ${v[2]*360/8}); color: ${v[0] < 5 ? 'white' : '#17202b'};` : ''"}])))

(defn demo-document []
  (let [doc (blank-document "5D example" 5)
        headings ["Experiment" "Baseline" "Growth" "Projected" "Δ change"]
        data [["North" 120 0.12] ["South" 85 0.08] ["East" 160 0.18]
              ["West" 95 -0.04] ["Central" 140 0.15]]
        value (fn [v] {:kind "value" :source (js/JSON.stringify (clj->js v))})
        formula (fn [s] {:kind "formula" :source s})
        doc (reduce (fn [d [x label]] (e/put-cell d [x 0] (value label))) doc (map-indexed vector headings))
        doc (reduce
              (fn [d [index [label base growth]]]
                (let [y (inc index)]
                  (-> d
                      (e/put-cell [0 y] (value label))
                      (e/put-cell [1 y] (value base))
                      (e/put-cell [2 y] (value growth))
                      (e/put-cell [3 y] (formula "(x,y,...rest) => Math.round($(1,y,...rest) * (1 + $(2,y,...rest)) * $(\"multiplier\"))"))
                      (e/put-cell [4 y] (formula "(x,y,...rest) => $(3,y,...rest) - $(1,y,...rest)")))))
              doc (map-indexed vector data))
        doc (-> doc
                (e/put-cell [0 7] (value "Total"))
                (e/put-cell [1 7] (formula "(x,y,...rest) => [1,2,3,4,5].reduce((sum,row) => sum + $(x,row,...rest), 0)"))
                (e/put-cell [3 7] (formula "(x,y,...rest) => [1,2,3,4,5].reduce((sum,row) => sum + $(x,row,...rest), 0)"))
                (e/put-cell [4 7] (formula "(x,y,...rest) => $(3,y,...rest) - $(1,y,...rest)"))
                (e/put-cell ["multiplier"] (value 1))
                (e/put-cell ["note"] (value "Change multiplier to recalculate the table"))
                (e/put-cell ["as_function"] {:kind "value" :source "x => x * 2"}))
        doc (reduce (fn [d [coord source]] (e/put-cell d coord (value source))) doc
                    [[[0 0 1] "A second slice"] [[1 1 1] 42] [[2 2 1] 84]
                     [[0 0 0 1] "Dimension four"] [[1 1 0 0 1] "Dimension five"]])]
    (assoc doc :rules
           [{:id (str (random-uuid)) :name "Column headings" :enabled true
             :coord "(x,y,...rest) => typeof x === 'number' && y === 0 && rest.every(v => v === 0)"
             :value "v => ['heading']"}
            {:id (str (random-uuid)) :name "Positive / negative" :enabled true
             :coord "(x,y) => typeof x === 'number' && x === 4 && y > 0"
             :value "v => typeof v === 'number' ? [v < 0 ? 'negative' : 'positive'] : []"}
            {:id (str (random-uuid)) :name "Totals row" :enabled true
             :coord "(x,y) => typeof x === 'number' && y === 7"
             :value "v => ['total']"}])))
