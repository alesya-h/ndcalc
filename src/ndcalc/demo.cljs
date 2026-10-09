(ns ndcalc.demo
  (:require [ndcalc.engine :as e]))

(def default-css
  ".heading {\n  color: var(--accent);\n  font-weight: 600;\n  background: var(--accent-soft);\n}\n.positive { color: var(--green); }\n.negative { color: var(--red); }\n.total {\n  background: var(--accent-soft);\n  font-weight: 600;\n}\n")

(defn blank-document [title dimensions]
  {:id (str (random-uuid)) :title title :dimensions dimensions
   :createdAt (.now js/Date) :updatedAt (.now js/Date)
   :cells {} :named {} :hyperplanes {} :aliases {} :cell-widths {} :rules [] :css default-css
   :view {:coord (vec (repeat dimensions 0)) :mapping (e/initial-mapping dimensions)}})

(defn color-document []
  (let [doc (blank-document "OKLCH vs LCH" 4)
        doc (reduce (fn [d c] (e/put-cell d c {:kind "formula" :source "(a,b,c,space) => [a,b,c,space]"}))
                    doc (for [space (range 2) c (range 8) b (range 8) a (range 8)] [a b c space]))
        doc (e/set-aliases doc {1 "lightness" 2 "chroma" 3 "hue" 4 "space"})
        doc (reduce (fn [doc [d c source]] (e/put-cell doc {:hyperplane [d c]} {:kind "formula" :source source}))
                    doc (concat (for [c (range 8)] [1 c "(d,c) => c*100/8"])
                                (for [c (range 8)] [2 c "(d,c) => c/8"])
                                (for [c (range 8)] [3 c "(d,c) => c*360/8"])))
        doc (-> doc
                (e/put-cell {:hyperplane [4 0]} {:kind "value" :source "'OKLCH'"})
                (e/put-cell {:hyperplane [4 1]} {:kind "value" :source "'LCH'"})
                (e/put-cell ["axes"] {:kind "value" :source "['Lightness (0–7)', 'Chroma (0–7)', 'Hue (0–7)', 'Color space: 0 OKLCH, 1 LCH']"})
                (e/put-cell ["notes"] {:kind "value" :source "'D4 compares OKLCH and CIELCH (D50). Lightness 0–87.5%, hue 0–315°. Chroma uses each space’s own scale: 0–0.35 vs 0–131.25, not equivalent colorimetric values. Out-of-sRGB colors are browser gamut-mapped. In 4D the two spaces appear side by side.'"}))]
    (assoc doc :view {:coord [0 0 3 0] :mapping [1 2]}
           :css ""
           :rules [{:id (str (random-uuid)) :name "OKLCH / CIELCH comparison" :enabled true
                    :coord "(...coord) => typeof coord[0] === 'number'"
                    :value "v => Array.isArray(v) ? `background-color: ${v[3] === 0 ? `oklch(${_.value('lightness')}% ${$$.chroma*0.4} ${$$.hue})` : `lch(${_.value('lightness')}% ${$$.chroma*150} ${$$.hue})`}; color: ${v[0] < 5 ? 'white' : '#17202b'};` : ''"}])))

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
    (let [doc (e/set-aliases doc {1 "metric" 2 "department" 3 "scenario" 4 "currency" 5 "period"})
          doc (reduce (fn [doc [c label]] (e/put-cell doc {:hyperplane [1 c]} (value label))) doc (map-indexed vector headings))
          doc (reduce (fn [doc [c label]] (e/put-cell doc {:hyperplane [2 c]} (value label))) doc
                      (concat [[0 "Legend"] [7 "Total"]] (map-indexed (fn [i row] [(inc i) (first row)]) data)))
          doc (reduce (fn [doc [d labels]] (reduce (fn [doc [c label]] (e/put-cell doc {:hyperplane [d c]} (value label)))
                                                  doc (map-indexed vector labels))) doc
                      [[3 ["Baseline" "Alternative"]] [4 ["USD" "EUR"]] [5 ["2026-01" "2026-02"]]])
          doc (reduce (fn [doc y] (e/put-cell doc [0 y] (formula "=> _.value('department')"))) doc (range 1 6))]
    (assoc doc :rules
           [{:id (str (random-uuid)) :name "Column headings" :enabled true
             :coord "(x,y,...rest) => typeof x === 'number' && y === 0 && rest.every(v => v === 0)"
             :value "v => ['heading']"}
            {:id (str (random-uuid)) :name "Positive / negative" :enabled true
             :coord "(x,y) => typeof x === 'number' && x === 4 && y > 0"
             :value "v => typeof v === 'number' ? [v < 0 ? 'negative' : 'positive'] : []"}
            {:id (str (random-uuid)) :name "Totals row" :enabled true
             :coord "(x,y) => typeof x === 'number' && y === 7"
             :value "v => ['total']"}]))))
