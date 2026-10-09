(ns ndcalc.examples
  (:require [ndcalc.demo :as demo]
            [ndcalc.engine :as e]))

(defn value [v] {:kind "value" :source (js/JSON.stringify (clj->js v))})
(defn rule [name coord source]
  {:id (str (random-uuid)) :name name :enabled true :coord coord :value source})
(def numeric "(...c) => typeof c[0] === 'number'")

(defn tensor-coords [shape]
  (if (empty? shape) [[]]
    (for [tail (tensor-coords (rest shape)) x (range (first shape))] (into [x] tail))))

(def axis-specs
  {"Heat diffusion" [["x" "(d,c) => +((c-3.5)*$(\"dx\")).toFixed(3)"]
                     ["y" "(d,c) => +((c-3.5)*$(\"dx\")).toFixed(3)"]
                     ["time" "(d,c) => c*$(\"dt\")"] ["material" "(d,c) => $(\"materials\")[c]"]]
   "Membrane eigenmodes" [["x" "(d,c) => c/7"] ["y" "(d,c) => c/7"]
                         ["mx" "(d,c) => c+1"] ["my" "(d,c) => c+1"]]
   "Product scenario planning" [["date" "(d,c) => `2026-${String(c+1).padStart(2,'0')}`"]
                                ["product" "(d,c) => $(\"products\")[c]"] ["region" "(d,c) => $(\"regions\")[c]"]
                                ["scenario" "(d,c) => $(\"scenarios\")[c]"] ["metric" "(d,c) => $(\"metrics\")[c]"]]
   "Beam design envelope" [["span" "(d,c) => 1+c*0.5"] ["load" "(d,c) => 1000*(c+1)"]
                           ["section" "(d,c) => $(\"depth\")[c]"] ["material" "(d,c) => $(\"materials\")[c]"]
                           ["metric" "(d,c) => $(\"metrics\")[c]"]]
   "Double-entry ledger" [["date" "(d,c) => `2026-${String(c+1).padStart(2,'0')}`"]
                         ["account" "(d,c) => $(\"accounts\")[c]"] ["department" "(d,c) => $(\"departments\")[c]"]
                         ["version" "(d,c) => $(\"versions\")[c]"]]
   "Interference atelier" [["x" "(d,c) => c-5.5"] ["y" "(d,c) => c-5.5"]
                           ["phase" "(d,c) => c"] ["motif" "(d,c) => $(\"motifs\")[c]"]]})

(defn tensor-document [title shape axes notes inputs source rules css]
  (let [doc (demo/blank-document title (count shape))
        doc (reduce #(e/put-cell %1 %2 {:kind "formula" :source source}) doc (tensor-coords shape))
        doc (reduce (fn [d [name v]] (e/put-cell d [name] (value v))) doc
                    (assoc inputs "axes" axes "notes" notes))]
    (let [specs (get axis-specs title)
          doc (e/set-aliases doc (into {} (map-indexed (fn [i [alias _]] [(inc i) alias]) specs)))
          doc (reduce (fn [d [i [_ source]]]
                        (reduce (fn [d c] (e/put-cell d {:hyperplane [(inc i) c]} {:kind "formula" :source source}))
                                d (range (nth shape i)))) doc (map-indexed vector specs))]
      (assoc doc :rules rules :css css))))

(defn heat-document []
  (tensor-document
    "Heat diffusion" [8 8 4 3]
    ["X sample (2 cm spacing)" "Y sample (2 cm spacing)" "Time (20 s steps)" "Material / diffusivity"]
    "Analytical 2D Gaussian heat diffusion on an infinite homogeneous plate. Temperatures in °C; diffusivity in m²/s. Synthetic parameters, not a finite-element simulation. Change the named inputs to recalculate. Formatting breathes gently with temperature."
    {"dx" 0.02 "dt" 20 "sigma" 0.035 "ambient" 20 "amplitude" 80
     "diffusivity" [0.00001 0.00004 0.00008] "materials" ["Low" "Medium" "High diffusivity"]}
    "(x,y,t,m) => { const s0=$(\"sigma\")**2, s=s0+4*$(\"diffusivity\")[m]*$$.time, r2=$$.x**2+$$.y**2; return +($(\"ambient\")+$(\"amplitude\")*s0/s*Math.exp(-r2/s)).toFixed(2); }"
    [(rule "Thermal map + breathing" numeric
           "v => typeof v === 'number' ? `background: oklch(72% 0.16 ${250-(v-20)*3}); color: #142030; animation: nd-thermal 4s ease-in-out infinite; animation-delay: ${-v/25}s;` : ''")]
    "@keyframes nd-thermal { 50% { filter: brightness(1.08); } }"))

(defn modes-document []
  (tensor-document
    "Membrane eigenmodes" [8 8 3 3]
    ["X / 7" "Y / 7" "X mode number − 1" "Y mode number − 1"]
    "Separable Dirichlet eigenfunctions sin((m+1)πx) sin((n+1)πy) on the unit square. Boundary amplitudes are zero. Signed, normalized amplitudes show nodal lines; D3/D4 select nine mode combinations."
    {"eigenvalue" {:description "λ = π²[(D3+1)² + (D4+1)²]"}}
    "(x,y,m,n) => +(Math.sin($$.mx*Math.PI*$$.x)*Math.sin($$.my*Math.PI*$$.y)).toFixed(4)"
    [(rule "Signed amplitude / nodal lines" numeric
           "v => typeof v === 'number' ? `background: oklch(${92-30*Math.abs(v)}% ${0.18*Math.abs(v)} ${v<0 ? 330 : 210}); color: #17202b; box-shadow: inset 0 0 0 ${Math.abs(v)<0.001 ? 2 : 0}px #708090;` : ''")]
    ""))

(defn business-document []
  (tensor-document
    "Product scenario planning" [12 3 3 3 4]
    ["Month (0 = January)" "Product" "Region" "Demand scenario" "Metric"]
    "Synthetic monthly operating model in USD. Revenue and cost depend on named assumptions; profit and margin reference other cells. D5: 0 revenue, 1 total cost, 2 operating profit, 3 margin %. Fixed cost is allocated equally across three regions. Edit an input or revenue cell to see dependent metrics update."
    {"products" ["Sensor" "Controller" "Accessory"] "regions" ["North" "South" "Export"]
     "scenarios" ["Downside" "Base" "Upside"] "metrics" ["Revenue USD" "Cost USD" "Profit USD" "Margin %"]
     "price" [120 80 45] "unit-cost" [65 42 24] "base-units" [90 140 210]
     "regional-demand" [1 1.35 0.75] "scenario-demand" [0.8 1 1.2]
     "fixed-cost" [1500 1800 1200] "monthly-growth" 0.015}
    "(m,p,r,s,k) => { if(k===2) return +($(m,p,r,s,0)-$(m,p,r,s,1)).toFixed(2); if(k===3) return +(100*$(m,p,r,s,2)/$(m,p,r,s,0)).toFixed(2); const units=$(\"base-units\")[p]*$(\"regional-demand\")[r]*$(\"scenario-demand\")[s]*(1+$(\"monthly-growth\"))**m*(1+0.12*Math.sin(2*Math.PI*m/12)); return +(k===0 ? units*$(\"price\")[p] : units*$(\"unit-cost\")[p]+$(\"fixed-cost\")[p]/3).toFixed(2); }"
    [(rule "Magnitude bars" numeric
           "v => typeof v === 'number' ? `background: linear-gradient(90deg, var(--accent-soft) ${Math.min(100,Math.abs(v)/250)}%, transparent 0);` : ''")
     (rule "Profit sign" "(m,p,r,s,k) => k === 2" "v => [v<0 ? 'loss' : 'profit']")
     (rule "Margin meter" "(m,p,r,s,k) => k === 3"
           "v => `background: linear-gradient(90deg, ${v<15 ? '#df884444' : '#30aa7744'} ${Math.max(0,Math.min(100,v))}%, transparent 0); font-weight: 600;`")]
    ".profit {color:var(--green)} .loss {color:var(--red); text-decoration:underline wavy;}"))

(defn beam-document []
  (tensor-document
    "Beam design envelope" [6 5 4 3 4]
    ["Span: 1 + D1 × 0.5 m" "UDL: (D2 + 1) kN/m" "Section depth" "Material" "Metric"]
    "Simply supported rectangular beam under uniform load: δ=5wL⁴/(384EI), σ=wL²h/(16I). D5 selects deflection mm, bending stress MPa, stress utilization %, or serviceability utilization %. Screening model only: ignores shear, buckling, joints and dynamics. Serviceability comparison uses L/360. All underlying inputs use SI units."
    {"width" 0.04 "depth" [0.04 0.06 0.08 0.10] "E" [200000000000 70000000000 11000000000]
     "allowable" [160000000 90000000 10000000] "materials" ["Steel" "Aluminium" "Timber"]
     "metrics" ["Deflection mm" "Stress MPa" "Stress utilization %" "Serviceability utilization %"]}
    "(s,q,h,m,k) => { const L=$$.span, w=$$.load, H=_.value('section'), I=$(\"width\")*H**3/12, stress=w*L**2*H/(16*I), delta=5*w*L**4/(384*$(\"E\")[m]*I); return +[1000*delta, stress/1e6, 100*stress/$(\"allowable\")[m], 100*delta/(L/360)][k].toFixed(3); }"
    [(rule "Magnitude bars" numeric "v => typeof v==='number' ? `background:linear-gradient(90deg,var(--accent-soft) ${Math.min(100,v*4)}%,transparent 0);` : ''")
     (rule "Utilization heatmap" "(s,q,h,m,k) => k >= 2"
           "v => `background: oklch(78% 0.13 ${v>100 ? 25 : 150}); color:#17202b;`")
     (rule "Stress / serviceability overload" "(s,q,h,m,k) => k >= 2"
           "v => v>100 ? 'background-image:repeating-linear-gradient(135deg,#d34c4933 0 6px,transparent 6px 12px); animation:nd-overload 3s ease-in-out infinite;' : ''")]
    "@keyframes nd-overload {50% {box-shadow:inset 0 0 0 3px #d34c49;}}"))

(defn ledger-document []
  (tensor-document
    "Double-entry ledger" [6 6 3 2]
    ["Month" "Account (last row is control)" "Department" "Actual / budget"]
    "Synthetic movement trial balance in USD, not opening/closing balances. Debits positive, credits negative. Sales split between cash and receivables; wages and operating expenses create cash/payables movements. The last row is an unposted control summing accounts 0–4: edit a posting to make it flag an imbalance. Calculations use integer cents before presentation."
    {"accounts" ["Cash" "Receivables" "Expenses" "Payables" "Revenue" "Control (not posted)"]
     "departments" ["Studio" "Production" "Retail"] "versions" ["Actual" "Budget"]}
    "(m,a,d,v) => { if(a===5) return +([0,1,2,3,4].reduce((sum,i)=>sum+$(m,i,d,v),0)).toFixed(2); const R=Math.round(1000000*(1+0.04*m)*(1+0.3*d)*(1+0.05*v)), wages=Math.round(R*0.55), expense=Math.round(R*0.08), ar=Math.round(R*0.15), ap=Math.round(expense*0.25); return [R-ar-wages-expense+ap, ar, wages+expense, -ap, -R][a]/100; }"
    [(rule "Debits / credits" numeric "v => typeof v==='number' ? [v<0 ? 'credit' : 'debit'] : []")
     (rule "Trial balance control" "(m,a) => a === 5" "v => [Math.abs(v)<0.005 ? 'balanced' : 'imbalance']")]
    ".debit {color:var(--green)} .credit {color:var(--accent)} .balanced {background:color-mix(in srgb,var(--green) 15%,transparent);font-weight:600;} .imbalance {color:var(--red);background:repeating-linear-gradient(45deg,#e3545422 0 5px,transparent 5px 10px);animation:nd-audit 2.5s ease-in-out infinite;} @keyframes nd-audit {50% {box-shadow:inset 0 0 0 2px var(--red);}}"))

(defn art-document []
  (tensor-document
    "Interference atelier" [12 12 4 3]
    ["X pixel" "Y pixel" "Phase frame" "Motif: radial / spiral / plaid"]
    "Procedural color studies with radial, spiral and plaid interference. Each formula returns an object; formatting turns its hue/lightness into conic gradients, animated hue rotation, and morphing corner shapes. Pure coordinate formulas: CSS supplies the motion, without timers or external assets. Enable Labels to inspect the objects. Reduced-motion preferences disable animations."
    {"motifs" ["Radial" "Spiral" "Plaid"] "frequency" 1.4}
    "(x,y,t,m) => { const X=$$.x,Y=$$.y,r=Math.hypot(X,Y),a=Math.atan2(Y,X),p=m===0 ? r : m===1 ? r+a*2 : Math.sin(X)+Math.cos(Y); return {hue:((p*35+t*45)%360+360)%360, light:65+15*Math.sin(p*$(\"frequency\")+t), phase:(x+y)/6}; }"
    [(rule "Conic pigments + shape morph" numeric
           "v => v && typeof v.hue==='number' ? `background: conic-gradient(from ${v.hue}deg, oklch(${v.light}% 0.18 ${v.hue}), oklch(85% 0.10 ${(v.hue+100)%360}), oklch(${v.light}% 0.18 ${v.hue})); color:#17202b; border-radius:15%; animation:nd-art-flow 8s ease-in-out infinite alternate; animation-delay:${-v.phase}s;` : ''")]
    "@keyframes nd-art-flow {to {filter:hue-rotate(35deg) saturate(1.2);border-radius:40% 5% 40% 5%; transform:scale(0.9);}}"))

(def examples
  [{:title "OKLCH vs LCH" :description "4D color-space comparison" :make demo/color-document}
   {:title "Heat diffusion" :description "Physics · transient heat · 4D" :make heat-document}
   {:title "Membrane eigenmodes" :description "Math · separable eigenfunctions · 4D" :make modes-document}
   {:title "Product scenario planning" :description "Business · revenue, cost and margin · 5D" :make business-document}
   {:title "Beam design envelope" :description "Engineering · span, load, section, material and metric · 5D" :make beam-document}
   {:title "Double-entry ledger" :description "Accounting · balanced postings and live controls · 4D" :make ledger-document}
   {:title "Interference atelier" :description "Art · animated conic gradients · 4D" :make art-document}])
