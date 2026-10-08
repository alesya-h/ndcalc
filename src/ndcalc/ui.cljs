(ns ndcalc.ui
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [ndcalc.engine :as e]
            [ndcalc.state :as s]
            [ndcalc.demo :as demo]
            [ndcalc.preview :as preview]))

(defn icon [kind & [size]]
  (let [paths {:cube ["M12 3 21 8v9l-9 5-9-5V8Z" "m3 8 9 5 9-5M12 13v9M12 3v10"]
               :home ["m3 10 9-7 9 7M5 9v12h14V9M9 21v-8h6v8"]
               :plus ["M12 5v14M5 12h14"]
               :sun ["M12 2v2M12 20v2M2 12h2M20 12h2m-3-9-1.5 1.5M6.5 17.5 5 19m14 0-1.5-1.5M6.5 6.5 5 5" "M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0"]
               :moon ["M21 13A9 9 0 0 1 11 3a9 9 0 1 0 10 10Z"]
               :download ["M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"]
               :upload ["M12 16V4m-5 5 5-5 5 5M4 16v5h16v-5"]
               :close ["m6 6 12 12M6 18 18 6"]
               :chevron ["m9 5 7 7-7 7"]
               :check ["m5 12 4 4L19 6"]
               :grid ["M3 3h18v18H3ZM3 9h18M3 15h18M9 3v18M15 3v18"]
               :edit ["m15 4 5 5M4 20l5-1L21 7l-4-4L5 15Z"]
               :trash ["M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"]
               :help ["M9 9a3 3 0 1 1 5 2c-2 1-2 2-2 3M12 18h.01" "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0"]}]
    [:svg {:width (or size 18) :height (or size 18) :viewBox "0 0 24 24" :fill "none"
           :stroke "currentColor" :stroke-width 1.6 :stroke-linecap "round" :stroke-linejoin "round"
           :aria-hidden true}
     (for [[i path] (map-indexed vector (get paths kind (:cube paths)))]
       ^{:key i} [:path {:d path}])]))

(defn tool-button [label glyph handler & [attrs]]
  [:button (merge {:class "button" :on-click handler :title label :aria-label label} attrs)
   [icon glyph] [:span label]])

(defn import-button []
  [:label.button.import-button {:title "Upload a JSON document"}
   [icon :upload] [:span "Import"]
   [:input {:type "file" :accept ".json,application/json" :aria-label "Import JSON document"
            :on-change (fn [event]
                         (s/import-file! (aget (.. event -target -files) 0))
                         (set! (.. event -target -value) ""))}]])

(defn theme-button []
  (let [dark? (= "dark" (:theme @s/app))]
    [tool-button (if dark? "Light theme" "Dark theme") (if dark? :sun :moon) s/toggle-theme!
     {:class "button icon-button"}]))

(defn topbar []
  (let [{:keys [route doc help]} @s/app]
    [:header.topbar {:inert (boolean (or (:editor @s/app) (:rule-editor @s/app) (:dialog @s/app)))}
     [:div.topbar-left
      [tool-button "Home" :home s/home! {:class "button icon-button home-button" :disabled (= route :loading)}]
      [:div.brand [icon :cube 26] [:span "nd" [:b "calc"]]]
      (when (= :editor route)
        [:<> [:span.breadcrumb-slash "/"]
         [:button.document-title {:on-click #(swap! s/app assoc :dialog {:type :rename :title (:title doc)})}
          (:title doc) [icon :edit 13]]
         [:span.dimension-badge (str (:dimensions doc) "D")]])]
     [:div.topbar-right
      (when (= :editor route)
        [:span.save-state [:span.status-dot] (:save-status @s/app)])
      [import-button]
      (when (= :editor route) [tool-button "Export" :download #(s/download! doc)])
      [theme-button]
      [:button.button.icon-button {:on-click #(swap! s/app update :help not) :title "Keyboard shortcuts (?)"
                                  :aria-label "Toggle keyboard shortcuts" :aria-pressed help}
       [icon :help]]]]))

(def shortcuts
  [["MOVE" [["← ↑ ↓ →" "Move in the visible plane"] ["h j k l" "Vim-style movement"] ["Tab / ⇧Tab" "Next / previous column"]]]
   ["DIMENSIONS" [["1–9" "Rotate a dimension into view"] ["0" "Rotate the null dimension"] ["g15 ↵" "Go to X coordinate 15"] ["G-15 ↵" "Go to Y coordinate −15"] ["b / B" "First active column / row"] ["e / E" "Last active column / row"]]]
   ["EDIT" [["↵ / i" "Edit the current cell"] ["f" "Edit as a formula"] ["v / Ctrl+v" "Toggle visual-block selection"] ["⇧ + arrows" "Extend a selection"] ["y / p" "Copy / paste cells"] ["Del" "Clear cell or selection"] ["d / D" "Clear current column / row"] ["u / Ctrl+z" "Undo"] ["Ctrl+⇧z" "Redo"] ["Ctrl+↵" "Apply an open editor"] ["Esc" "Cancel / normal mode"]]]
   ["PANELS" [["n" "New named cell"] ["c" "Conditional formatting"] ["t" "Plane / read-only 3D"] ["PgUp / PgDn" "Move Z in 3D"] ["?" "Toggle this cheatsheet"]]]])

(defn help-sidebar []
  [:aside.help-sidebar {:aria-label "Keyboard shortcuts"}
   [:div.sidebar-heading [:h2 "Keyboard shortcuts"]
    [:button.button.icon-button {:on-click #(swap! s/app assoc :help false) :aria-label "Close shortcuts"} [icon :close 16]]]
   (for [[heading keys] shortcuts]
     ^{:key heading}
     [:section.shortcut-section [:h3 heading]
      (for [[key description] keys]
        ^{:key key} [:div.shortcut [:kbd key] [:span description]])])
   [:div.help-note "Lowercase axis operations use " [:b "X"] ". Uppercase uses " [:b "Y"] ". Visual selection survives plane and slice changes, selecting every cell between its n-dimensional corners. In 3D, editing is disabled."]])

(defn slice-input [dimension value]
  ;; Keep intermediate text (notably "-") so controlled inputs allow negative typing.
  (r/with-let [draft (r/atom nil)]
    [:input {:type "text" :input-mode "numeric"
             :value (if (nil? @draft) (str value) @draft)
             :aria-label (str "Dimension " dimension " slice coordinate")
             :on-focus #(reset! draft (str value))
             :on-change (fn [event]
                          (let [text (.. event -target -value)]
                            (reset! draft text)
                            (when (re-matches #"-?\d+" text)
                              (s/set-slice! dimension (js/Number text)))))
             :on-key-down #(when (= "Enter" (.-key %)) (.blur (.-target %)))
             :on-blur (fn [_]
                        (when (and @draft
                                   (or (not (re-matches #"-?\d+" @draft))
                                       (not (e/safe-integer? (js/Number @draft)))))
                          (s/notify! "Slice coordinates must be signed safe integers."))
                        (reset! draft nil))}]))

(defn axis-select [axis]
  (let [n (get-in @s/app [:doc :dimensions]) m (s/mapping)]
    [:label.axis-select [:span (if (zero? axis) "X" "Y")]
     [:select {:value (nth m axis) :aria-label (str (if (zero? axis) "X" "Y") " dimension")
               :on-change #(s/set-mapping! axis (js/Number (.. % -target -value)))}
      (for [d (range (inc n))] ^{:key d} [:option {:value d} (if (zero? d) "∅ null" (str "D" d))])]]))

(defn dimension-bar []
  (let [{:keys [doc view cube-axes]} @s/app n (:dimensions doc)
        c (get-in doc [:view :coord]) [x y] (s/mapping)
        axes (if (= :cube view) cube-axes [x y])]
    [:div.dimension-bar
     [:div.dimension-chips
      (when (zero? n) [:div.dim-chip.active [:b "∅"] [:span "origin []"]])
      (for [d (range 1 (inc n))]
        (let [axis-index (first (keep-indexed #(when (= d %2) %1) axes)) active (some? axis-index)]
          ^{:key d}
          [:div {:class (str "dim-chip " (if active "active" "inactive"))}
           [:button {:title (str "Rotate dimension " d " into the plane") :on-click #(s/switch! d)}
            [:span.dim-name (str "D" d)]
            [:span.axis-tag (if active (nth ["X" "Y" "Z"] axis-index) "fixed")]]
           [slice-input d (nth c (dec d))]]))]
     [:button.button.dimension-settings {:on-click #(swap! s/app assoc :dialog {:type :dimensions :n n})
                                        :disabled (= view :cube) :title "Change the table's dimension count"}
      [icon :plus 14] "Dimensions"]]))

(defn format-content [runtime coord cell & [extra-class]]
  (let [result ((:evaluate runtime) coord) formatting ((:format runtime) coord result)
        error (:error result) text (if error (str "#ERROR " error) (if cell (e/stringify (:value result)) ""))
        tooltip (str (e/coord-label coord) (when cell (str "\n" (:kind cell) ": " (:source cell)))
                     "\n" text (when (seq (:errors formatting)) (str "\nFormatting: " (str/join "\n" (:errors formatting)))))]
    [:span {:class (str "cell-content " extra-class " " (str/join " " (:classes formatting))
                       (when error " cell-error"))
            :title tooltip
            :ref (fn [el] (when el (set! (.. el -style -cssText) (:style formatting))) nil)}
     (when (= "formula" (:kind cell)) [:span.formula-dot {:aria-label "Formula"} "ƒ"])
     [:span.cell-text text]
     (when (seq (:errors formatting)) [:span.format-warning {:title (str/join "\n" (:errors formatting))} "!"])]))

(defn grid []
  (r/with-let [node (atom nil) observer (atom nil)
               measure (fn []
                         (when @node
                           (let [cols (max 1 (min 24 (js/Math.floor (/ (- (.-clientWidth @node) 46) 126))))
                                 rows (max 1 (min 40 (js/Math.floor (/ (- (.-clientHeight @node) 34) 35))))
                                 size [cols rows]]
                             (when-not (= size (:grid-size @s/app))
                               (swap! s/app assoc :grid-size size)
                               (s/ensure-visible!)))))
               ref-fn (fn [el]
                        (when-not (identical? el @node)
                          (when @observer (.disconnect @observer))
                          (reset! node el)
                          (when el
                            (reset! observer (js/ResizeObserver. (fn [_ _] (measure))))
                            (.observe @observer el)
                            (measure))) nil)]
    (let [{:keys [doc viewport grid-size anchor named-focus]} @s/app
          [x y :as mapping] (s/mapping) current (get-in doc [:view :coord])
          [left top] viewport [cols rows] grid-size
          xs (if (zero? x) [0] (range left (+ left cols)))
          ys (if (zero? y) [0] (range top (+ top rows)))
          bounds (e/active-bounds doc) runtime (s/runtime)]
      [:div {:class (str "grid-container sheet-scope" (when (zero? x) " null-x") (when (zero? y) " null-y"))
             :ref ref-fn :data-testid "grid"}
       [:table.sheet {:role "grid" :aria-label "N-dimensional spreadsheet" :aria-rowcount (count ys) :aria-colcount (count xs)}
        [:colgroup [:col.row-head-col] (for [a xs] ^{:key a} [:col])]
        [:thead [:tr
                 [:th.corner {:title "X coordinates across, Y coordinates down"} "Y" [:span " / X"]]
                 (for [a xs]
                   ^{:key a} [:th {:scope "col" :class (when (= a (e/axis-value current x)) "current-axis")}
                             (if (zero? x) "∅" a)])]]
        [:tbody
         (for [b ys]
           ^{:key b}
           [:tr [:th.row-head {:scope "row" :class (when (= b (e/axis-value current y)) "current-axis")}
                 (if (zero? y) "∅" b)]
            (for [a xs]
              (let [c (e/plane-coord current mapping a b) cell (e/cell-at doc c)
                    current? (and (not named-focus) (= c current))
                    selected? (and anchor (e/in-block? anchor current c))
                    active? (e/active? bounds c)]
                ^{:key a}
                [:td {:role "gridcell" :tab-index (if current? 0 -1)
                      :aria-label (e/coord-key c) :aria-selected (boolean (or selected? current?))
                      :data-coord (e/coord-key c)
                      :class (str (when-not active? "out-of-bounds ") (when selected? "selected ") (when current? "current "))
                      :on-click (fn [event] (.focus (.-currentTarget event)) (s/select! c (.-shiftKey event)))
                      :on-double-click #(do (s/select! c false) (s/open-editor! nil))}
                 [format-content runtime c cell]]))])]]])
    (finally (when @observer (.disconnect @observer)))))

(defn cube-slider [label key low high]
  [:label.tilt-control label
   [:input {:type "range" :min low :max high :value (get (s/cube-options) key)
            :aria-label (str "3D " (str/lower-case label))
            :on-change #(s/set-cube-option! key (js/Number (.. % -target -value)))}]])

(defn cube-layer [context window options scene layer at-z stacked?]
  (let [{:keys [doc current axes runtime bounds anchor]} context
        [x y z] axes [xs ys _] (:ranges window) [nx _ nz] (:shape window)]
    [:section {:class (if stacked? "cube-layer" "cube-slice")
               :aria-label (str "D" z " slice " at-z)
               :style (merge {:width (:width scene)}
                             (if stacked?
                               {:height (:height scene)
                                :transform (str "translateZ(" (* (- layer (/ (dec nz) 2)) (:gap options)) "px)")}
                               {:zoom (/ (:zoom options) 100)}))}
     [:div.cube-layer-label
      {:style (when stacked? {:transform (str "rotateZ(" (- (:rotation options)) "deg) rotateX(" (- (:tilt options)) "deg)")})}
      (str "D" z " = " at-z)]
     [:div.cube-layer-grid {:style {:grid-template-columns (str "repeat(" nx ", minmax(0,1fr))")
                                    :grid-auto-rows (str (:row-height scene) "px")}}
      (for [b ys a xs]
        (let [at (-> current (e/set-axis x a) (e/set-axis y b) (e/set-axis z at-z))
              selected? (and anchor (e/in-block? anchor current at))]
          ^{:key (e/coord-key at)}
          [:button {:type "button" :class (str "cube-cell " (when-not (e/active? bounds at) "out-of-bounds ")
                                               (when selected? "selected ") (when (= at current) "cube-current"))
                    :tab-index (if (= at current) 0 -1) :aria-label (e/coord-key at) :aria-pressed (= at current)
                    :data-coord (e/coord-key at)
                    :on-click (fn [event] (.focus (.-currentTarget event)) (s/select! at (.-shiftKey event)))
                    :on-double-click #(do (s/select! at false) (s/toggle-3d!))}
           [format-content runtime at (e/cell-at doc at)]
           [:span.cube-coord (e/coord-key at)]]))]]))

(defn cube []
  (r/with-let [stage-size (r/atom [800 500]) node (atom nil) observer (atom nil)
               ref-fn (fn [el]
                        (when-not (identical? el @node)
                          (when @observer (.disconnect @observer))
                          (reset! node el)
                          (when el
                            (reset! observer
                                    (js/ResizeObserver.
                                      (fn [_ _]
                                        (let [size [(.-clientWidth el) (.-clientHeight el)]]
                                          (when-not (= size @stage-size) (reset! stage-size size))))))
                            (.observe @observer el))) nil)]
    (let [{:keys [doc cube-axes cube-fit anchor]} @s/app options (s/cube-options)
          window (s/cube-window) scene (preview/scene (:shape window) options @stage-size)
          stacked? (= "stack" (:layout options))
          context {:doc doc :current (get-in doc [:view :coord]) :axes cube-axes :runtime (s/runtime)
                   :bounds (e/active-bounds doc) :anchor anchor}]
      [:div {:class (str "cube-view sheet-scope " (if (:labels options) "cube-with-labels" "cube-no-labels"))}
       [:div.cube-controls
        (for [[i label] (map-indexed vector ["X" "Y" "Z"])]
          ^{:key i}
          [:div.cube-axis-control
           [:label.axis-select [:span label]
            [:select {:value (nth cube-axes i) :aria-label (str "3D " label " dimension")
                      :on-change #(s/set-cube-axis! i (js/Number (.. % -target -value)))}
             (for [d (range 1 (inc (:dimensions doc)))] ^{:key d} [:option {:value d} (str "D" d)])]]
           [:label.cube-size "size"
            [:input {:type "number" :min 1 :max preview/max-axis-size :step 1 :value (nth (:size options) i)
                     :aria-label (str "3D " label " size")
                     :on-change #(s/set-cube-size! i (js/Number (.. % -target -value)))}]]])
        [:button.button.compact {:on-click s/fit-cube! :disabled (nil? (:bounds context))} "Fit active bounds"]
        [:label.cube-check
         [:input {:type "checkbox" :checked (not cube-fit) :aria-label "3D follow current cell"
                  :on-change #(if (.. % -target -checked) (swap! s/app assoc :cube-fit false) (s/fit-cube!))}]
         "Follow cell"]
        [:div.segmented
         (for [[layout label] [["stack" "Stack"] ["slices" "Slices"]]]
           ^{:key layout}
           [:button {:class (when (= layout (:layout options)) "active") :aria-pressed (= layout (:layout options))
                     :on-click #(s/set-cube-option! :layout layout)} label])]
        [:label.cube-check
         [:input {:type "checkbox" :checked (:labels options) :aria-label "3D show labels"
                  :on-change #(s/set-cube-option! :labels (.. % -target -checked))}] "Labels"]
        [:span.readonly-tag "READ ONLY"]]
       [:div.cube-camera-controls
        [cube-slider "Tilt" :tilt 15 80] [cube-slider "Rotation" :rotation -180 180]
        [cube-slider "Zoom" :zoom 25 200] [cube-slider "Layer gap" :gap 16 160]
        [:button.button.compact {:on-click #(swap! s/app assoc :cube-options
                                                  (merge (s/cube-options) (select-keys preview/default-options [:tilt :rotation :zoom :gap])))} "Reset camera"]
        [:button.button.compact {:on-click #(s/move-depth! -1) :aria-label "Previous Z slice"} "Z −"]
        [:button.button.compact {:on-click #(s/move-depth! 1) :aria-label "Next Z slice"} "Z +"]
        [:button.button.compact {:on-click s/toggle-3d!} "Open in plane"]
        [:span.cube-count (str (:total window) " cells")]]
       (if stacked?
         [:div.cube-stage {:ref ref-fn}
          [:div.cube-stack {:style {:width (:width scene) :height (:height scene)
                                   :transform (str "translate(-50%, -50%) scale(" (:scale scene) ") rotateX("
                                                   (:tilt options) "deg) rotateZ(" (:rotation options) "deg)")}}
           (for [[layer at-z] (map-indexed vector (nth (:ranges window) 2))]
             ^{:key at-z} [cube-layer context window options scene layer at-z true])]]
         [:div.cube-slices
          (for [[layer at-z] (map-indexed vector (nth (:ranges window) 2))]
            ^{:key at-z} [cube-layer context window options scene layer at-z false])])
       [:p.cube-caption
        [:span.cube-ranges (str (str/join " × " (:shape window)) " · "
                               (str/join " · " (map (fn [label axis start end] (str label " D" axis " " start "…" end))
                                                    ["X" "Y" "Z"] cube-axes (:start window) (:end window))))]
        (when (:clipped? window) [:span.preview-limit " · Bounded preview, not the entire active volume."])
        [:br] "Arrows select; PgUp/PgDn move Z. Click a cell to select, double-click to open its plane. Hover for value/source."]])
    (finally (when @observer (.disconnect @observer)))))

(defn cell-bar []
  (let [{:keys [doc view anchor]} @s/app c (s/coord) cell (e/cell-at doc c)
        result ((:evaluate (s/runtime)) c)]
    [:div.cell-bar
     [:span.coordinate-label (e/coord-label c)]
     [:span.cell-kind (if cell (:kind cell) "empty")]
     [:button.cell-source {:on-click #(s/open-editor! nil) :disabled (= view :cube)
                           :title "Edit cell (Enter)" :aria-label "Edit current cell"}
      (if cell (:source cell) [:span.placeholder "Press Enter to set a value, or f for a formula…"])]
     (when (:error result) [:span.cell-bar-error {:title (:error result)} "Evaluation error"])
     (when anchor
       [:span.selection-count {:title (str "Selection corners: " (e/coord-key anchor) " → " (e/coord-key c))}
        (str (e/block-size anchor c) " selected")])]))

(defn plane-toolbar []
  (let [{:keys [view doc]} @s/app]
    [:div.plane-toolbar
     [:div.toolbar-left
      (when (= view :plane) [:<> [axis-select 0] [axis-select 1] [:span.toolbar-divider]])
      [:span.plane-description (if (= view :cube) "Volume preview" "Editable slice")]]
     [:div.toolbar-right
      [:div.segmented
       [:button {:class (when (= view :plane) "active") :on-click #(swap! s/app assoc :view :plane)} [icon :grid 15] "Plane"]
       [:button {:class (when (= view :cube) "active") :disabled (< (:dimensions doc) 3)
                 :on-click #(when (= :plane view) (s/toggle-3d!))} [icon :cube 15] "3D"]]
      [tool-button "Edit" :edit #(s/open-editor! nil) {:class "button compact" :disabled (= view :cube)}]]]))

(defn named-panel []
  (let [{:keys [doc named-focus view]} @s/app runtime (s/runtime)]
    [:section.named-panel
     [:div.panel-heading [:h2 "Named cells"]
      [:button.button.icon-button {:on-click s/new-named! :disabled (= view :cube) :title "New named cell (n)" :aria-label "New named cell"} [icon :plus]]]
     (if (empty? (:named doc))
       [:div.panel-empty [:p "No named cells."] [:span "Use named cells with $(\"name\")."]]
       [:div.named-list
        (for [[name cell] (sort-by key (:named doc))]
          ^{:key name}
          [:div {:class (str "named-card" (when (= name named-focus) " active"))}
           [:div.named-card-top
            [:button.named-name {:on-click #(s/select! [name] false)
                                 :on-double-click #(do (s/select! [name] false) (s/open-editor! nil))}
             [:span "$ "] name]
            [:span.cell-kind (:kind cell)]
            [:button.button.icon-button.tiny {:on-click #(do (s/select! [name] false) (s/open-editor! nil))
                                             :disabled (= view :cube) :aria-label (str "Edit named cell " name)} [icon :edit 14]]
            [:button.button.icon-button.tiny {:on-click #(s/change! (fn [d] (e/put-cell d [name] nil)))
                                             :disabled (= view :cube) :aria-label (str "Delete named cell " name)} [icon :trash 14]]]
           [format-content runtime [name] cell]])])
     [:div.panel-tip [:code "$(\"name\")"] [:p "Named cells can contain values or formulas. A function can also be just a value."]]]))

(defn rules-panel []
  (let [{:keys [doc view]} @s/app editable (= view :plane)]
    [:section.rules-panel
     [:div.panel-heading [:div [:h2 "Conditional formatting"] [:p "Ordered rules. Later styles win."]]
      [:button.button.icon-button {:on-click #(s/open-rule! nil) :disabled (not editable) :aria-label "Add formatting rule"} [icon :plus]]]
     [:div.rule-list
      (when (empty? (:rules doc)) [:div.panel-empty [:p "No formatting rules."] [:span "Add a rule, or use () => true for static formatting."]])
      (for [[index rule] (map-indexed vector (:rules doc))]
        ^{:key (:id rule)}
        [:article.rule-card {:draggable editable
                             :on-drag-start #(do (.setData (.-dataTransfer %) "text/plain" (str index))
                                                 (set! (.. % -dataTransfer -effectAllowed) "move"))
                             :on-drag-over #(.preventDefault %)
                             :on-drop #(do (.preventDefault %)
                                           (let [from (js/Number (.getData (.-dataTransfer %) "text/plain"))]
                                             (s/reorder-rule! from index)))}
         [:div.rule-top
          [:span.rule-order (str (inc index) ".")]
          [:input {:type "checkbox" :checked (:enabled rule) :disabled (not editable)
                   :aria-label (str "Enable " (:name rule))
                   :on-change #(s/change! (fn [d] (update-in d [:rules index :enabled] not)))}]
          [:button.rule-name {:on-click #(s/open-rule! rule) :disabled (not editable) :title "Edit formatting rule"} (:name rule)]
          [:button.button.icon-button.tiny {:on-click #(s/open-rule! rule) :disabled (not editable)
                                           :aria-label (str "Edit " (:name rule)) :title "Edit formatting rule"} [icon :edit 14]]
          [:button.button.icon-button.tiny {:on-click #(s/reorder-rule! index (dec index)) :disabled (or (not editable) (zero? index)) :aria-label (str "Move " (:name rule) " up")} "↑"]
          [:button.button.icon-button.tiny {:on-click #(s/reorder-rule! index (inc index)) :disabled (or (not editable) (= index (dec (count (:rules doc))))) :aria-label (str "Move " (:name rule) " down")} "↓"]
          [:button.button.icon-button.tiny {:on-click #(s/change! (fn [d] (update d :rules (fn [rules] (vec (remove (fn [r] (= (:id r) (:id rule))) rules))))))
                                           :disabled (not editable) :aria-label (str "Delete " (:name rule))} [icon :trash 13]]]
         [:div.rule-code [:span "where"] [:code (:coord rule)]]
         [:div.rule-code [:span "apply"] [:code (:value rule)]]
         (when-let [error (:error (nth (:rules (s/runtime)) index))] [:p.inline-error error])])]
     [:button.button.add-rule {:on-click #(s/open-rule! nil) :disabled (not editable)} [icon :plus 16] "Add rule"]
     [:div.panel-tip
      [:p "Coordinate predicates receive spread coordinates—or a single string for a named cell. Value predicates return CSS classes or a style string."]
      [:p "Numeric cells are formatted only inside the active hypercube. Named-cell rules still apply."]]]))

(defn css-panel []
  (let [{:keys [doc css-draft view]} @s/app]
    [:section.css-panel
     [:div.panel-heading [:div [:h2 "Stylesheet"] [:p "CSS, scoped to your table."]]]
     [:textarea.code-editor.css-editor {:value (or css-draft (:css doc)) :spell-check false :aria-label "Table CSS"
                                       :read-only (= view :cube)
                                       :on-change #(swap! s/app assoc :css-draft (.. % -target -value))
                                       :on-key-down #(when (and (= view :plane) (or (.-ctrlKey %) (.-metaKey %)) (= "Enter" (.-key %)))
                                                       (.preventDefault %)
                                                       (s/change! (fn [d] (assoc d :css (:css-draft @s/app)))))}]
     [:button.button.primary {:disabled (or (= view :cube) (= css-draft (:css doc)))
                             :on-click #(do (s/change! (fn [d] (assoc d :css (:css-draft @s/app)))) (s/notify! "Stylesheet applied."))}
      [icon :check 16] "Apply CSS"]
     [:div.panel-tip [:code "v => ['positive']"] [:p "Return your class names from a rule. Or return an inline declaration like \"color: coral;\". Styles are shared by the plane, named cells, and 3D view."]]]))

(defn inspector []
  (let [panel (:panel @s/app)]
    [:aside.inspector.sheet-scope {:aria-label "Table inspector"}
     [:div.inspector-tabs
      (for [[tab label] [[:named "Named cells"] [:rules "Rules"] [:css "CSS"]]]
        ^{:key tab} [:button {:class (when (= panel tab) "active") :aria-pressed (= panel tab)
                             :on-click #(swap! s/app assoc :panel tab)} label
                     (when (= tab :named) [:span.tab-count (count (get-in @s/app [:doc :named]))])])]
     [:div.inspector-content (case panel :named [named-panel] :rules [rules-panel] :css [css-panel])]]))

(defn statusbar []
  (let [{:keys [mode view doc command]} @s/app bounds (e/active-bounds doc)]
    [:footer.statusbar
     [:div.status-left
      [:span {:class (str "mode-badge " (name mode))} (if (= view :cube) "VIEW ONLY" (str/upper-case (name mode)))]
      (if command
        [:div.command-line [:span (if (zero? (:axis command)) "g" "G")] (:text command) [:span.command-caret "▏"] [:small "Enter to jump · Esc to cancel"]]
        [:span.status-hint (cond (= view :cube) "arrows select · PgUp/PgDn move Z · t for plane"
                                (= mode :visual) "arrows / dimensions extend · Enter fill · y copy · Del clear"
                                :else "Enter to edit · v to select · ? for shortcuts")])]
     [:div.status-right
      [:span.bounds-label {:title "Minimal and maximal populated coordinates, across every dimension"}
       (if bounds (str (e/coord-key (:start bounds)) " → " (e/coord-key (:end bounds))) "No active area")]
      [:span (str (count (:cells doc)) " cells")]]]))

(defn editor-workspace []
  [:<>
   [dimension-bar]
   [:div.editor-body
    [:main.canvas [plane-toolbar] [cell-bar] (if (= :cube (:view @s/app)) [cube] [grid])]
    [inspector]]
   [statusbar]])

(defn home-page []
  (let [docs (:documents @s/app)]
    [:main.home-page
     [:div.library-heading
      [:h1 "Tables"] [:span (count docs)]
      [:button.button.primary.new-document {:on-click #(swap! s/app assoc :dialog {:type :create :title "Untitled table" :n 2})}
       [icon :plus 18] "New table"]]
     [:div.library-examples [:span "Examples"]
      [:button.button.compact {:on-click #(s/open-document! (demo/demo-document))} "Open the 5D example"]
      [:button.button.compact {:on-click s/open-color-example!} "Open OKLCH color cube"]]
     (if (empty? docs)
       [:div.library-empty [:p "No tables."]]
       [:div.document-grid
        (for [doc docs]
          ^{:key (:id doc)}
          [:article.document-card
           [:button.document-open {:on-click #(s/open-document! doc)}
            [:span.dimension-badge (str (:dimensions doc) "D")]
            [:div.document-info [:h3 (:title doc)]
             [:p (str (count (:cells doc)) " cells · " (count (:named doc)) " named")]]]
           [:div.document-card-footer
            [:span (str "Edited " (.toLocaleDateString (js/Date. (:updatedAt doc)) js/undefined #js {:month "short" :day "numeric"}))]
            [:button.button.icon-button.tiny {:on-click #(s/download! doc) :aria-label (str "Export " (:title doc))} [icon :download 15]]
            [:button.button.icon-button.tiny {:on-click #(swap! s/app assoc :dialog {:type :delete :document doc}) :aria-label (str "Delete " (:title doc))} [icon :trash 15]]]])])
     [:p.local-first-note "Stored in this browser. Export a backup before clearing browser data."]]))

(defn modal-shell [title subtitle content actions close-fn]
  (r/with-let [previous-focus (.-activeElement js/document)]
    [:div.modal-backdrop
     {:on-mouse-down #(when (identical? (.-target %) (.-currentTarget %)) (close-fn))
      :on-key-down (fn [event]
                     (when (= "Tab" (.-key event))
                       (let [items (array-seq (.querySelectorAll (.-currentTarget event)
                                              "button:not(:disabled), input, select, textarea, [tabindex='0']"))
                             first-el (first items) last-el (last items) active (.-activeElement js/document)]
                         (cond
                           (and (.-shiftKey event) (identical? active first-el))
                           (do (.preventDefault event) (.focus last-el))
                           (and (not (.-shiftKey event)) (identical? active last-el))
                           (do (.preventDefault event) (.focus first-el))))))}
     [:section.modal {:role "dialog" :aria-modal true :aria-label title}
      [:div.modal-heading [:div [:h2 title] (when subtitle [:p subtitle])]
       [:button.button.icon-button {:on-click close-fn :aria-label "Close dialog"} [icon :close]]]
      content
      [:div.modal-actions [:span.modal-key-hint "Esc to cancel"] actions]]]
    (finally (when (and previous-focus (.-isConnected previous-focus)) (.focus previous-focus)))))

(defn cell-editor []
  (let [{:keys [coords kind source error new-name name focus-source]} (:editor @s/app)]
    (when focus-source
      (r/after-render
        (fn []
          (when (get-in @s/app [:editor :focus-source])
            (when-let [el (.querySelector js/document "textarea[aria-label='Cell JavaScript source']")]
              (.focus el)
              (.setSelectionRange el (count (.-value el)) (count (.-value el)))
              (swap! s/app assoc-in [:editor :focus-source] false))))))
    [modal-shell (if new-name "New named cell" (if (> (count coords) 1) (str "Fill " (count coords) " cells") "Edit cell"))
     (when-not new-name (str (e/coord-label (first coords)) (when (> (count coords) 1) (str " … " (e/coord-label (last coords))))))
     [:div.modal-content
      (when new-name [:label.field "NAME" [:input {:auto-focus true :value name :placeholder "my_named_cell" :aria-label "Cell name"
                                                  :on-change #(swap! s/app assoc-in [:editor :name] (.. % -target -value))}]])
      [:div.kind-picker {:role "group" :aria-label "Cell type"}
       (for [[k label description] [["value" "Value" "Any JavaScript expression"] ["formula" "Formula" "A function that computes this cell"]]]
         ^{:key k}
         [:button {:class (when (= kind k) "active") :aria-pressed (= kind k)
                   :on-click #(s/set-editor-kind! k)}
          [:span (if (= k "formula") "ƒ" "≡")] [:div [:b label] [:small description]]])]
      [:label.field (if (= kind "formula") "JAVASCRIPT FUNCTION" "JAVASCRIPT EXPRESSION")
       [:textarea.code-editor {:auto-focus (not new-name) :value source :spell-check false :rows 6
                               :aria-label "Cell JavaScript source"
                               :placeholder (if (= kind "formula") (s/editor-template (:editor @s/app)) "42, \"hello\", { answer: 42 }, or x => x * 2")
                               :on-change #(swap! s/app assoc-in [:editor :source] (.. % -target -value))
                               :on-key-down #(when (and (or (.-ctrlKey %) (.-metaKey %)) (= "Enter" (.-key %))) (.preventDefault %) (s/save-editor!))}]]
      [:p.editor-note (if (= kind "formula") "Coordinates are passed as function arguments. Read numeric or named cells with $(…). Missing cells return undefined."
                         "A function entered as a Value stays a function—it is never called by the formula engine. Wrap text in quotes.")]
      (when error [:div.inline-error {:role "alert"} error])]
     [:button.button.primary {:on-click s/save-editor!} [icon :check 16] "Apply" [:kbd "Ctrl ↵"]]
     #(swap! s/app assoc :editor nil)]))

(defn rule-editor []
  (let [rule (:rule-editor @s/app)]
    [modal-shell "Formatting rule" "Match coordinates first, then style the computed value."
     [:div.modal-content {:on-key-down #(when (and (or (.-ctrlKey %) (.-metaKey %)) (= "Enter" (.-key %)))
                                         (.preventDefault %) (s/save-rule!))}
      [:label.field "RULE NAME" [:input {:value (:name rule) :auto-focus true :aria-label "Rule name"
                                        :on-change #(swap! s/app assoc-in [:rule-editor :name] (.. % -target -value))}]]
      [:label.field "COORDINATE PREDICATE · RETURNS BOOLEAN"
       [:textarea.code-editor {:value (:coord rule) :rows 3 :spell-check false :aria-label "Coordinate predicate"
                               :on-change #(swap! s/app assoc-in [:rule-editor :coord] (.. % -target -value))}]]
      [:label.field "VALUE PREDICATE · RETURNS CLASSES OR CSS"
       [:textarea.code-editor {:value (:value rule) :rows 3 :spell-check false :aria-label "Value predicate"
                               :on-change #(swap! s/app assoc-in [:rule-editor :value] (.. % -target -value))}]]
      [:label.rule-enabled
       [:input {:type "checkbox" :checked (:enabled rule) :aria-label "Rule enabled"
                :on-change #(swap! s/app assoc-in [:rule-editor :enabled] (.. % -target -checked))}]
       "Enabled"]
      [:p.editor-note "Static style: () => true. Named-cell match: name => name === 'my_named_cell'. Classes: v => ['heading']. Inline style: v => 'color: coral;'."]
      (when (:error rule) [:div.inline-error {:role "alert"} (:error rule)])]
     [:button.button.primary {:on-click s/save-rule!} [icon :check 16] "Apply rule"]
     #(swap! s/app assoc :rule-editor nil)]))

(defn submit-dialog! []
  (let [{:keys [type title n document]} (:dialog @s/app)]
    (case type
      :create (s/create-document! title (js/Number n))
      :rename (when-not (str/blank? title)
                (s/change! #(assoc % :title (str/trim title))) (swap! s/app assoc :dialog nil))
      :dimensions (try (s/change! #(e/resize-dimensions % (js/Number n)))
                       (swap! s/app assoc :dialog nil :anchor nil :named-focus nil :mode :normal)
                       (s/ensure-visible!)
                       (catch :default err (swap! s/app assoc-in [:dialog :error] (.-message err))))
      :trust (s/open-document! document)
      :delete (do (s/delete-document! document) (swap! s/app assoc :dialog nil)) nil)))

(defn generic-dialog []
  (let [{:keys [type title n document error]} (:dialog @s/app)
        headings {:create "New table" :rename "Rename table" :dimensions "Table dimensions"
                  :trust "Trust this document?" :delete "Delete this table?"}]
    [modal-shell (get headings type) nil
     [:form.modal-content {:on-submit #(do (.preventDefault %) (submit-dialog!))}
      (when (#{:create :rename} type)
        [:label.field "TITLE" [:input {:auto-focus true :value title :aria-label "Table title"
                                       :on-change #(swap! s/app assoc-in [:dialog :title] (.. % -target -value))}]])
      (when (#{:create :dimensions} type)
        [:label.field "DIMENSIONS" [:input {:type "number" :min 0 :max 32 :step 1 :value n :aria-label "Dimension count"
                                            :auto-focus (= type :dimensions)
                                            :on-change #(swap! s/app assoc-in [:dialog :n] (.. % -target -value))}]
         [:span.field-help "0 = one cell · 1 = a row · 2 = a table · 3+ = a hypertable"]])
      (when (= type :dimensions) [:p.editor-note "Dimensions containing non-zero populated coordinates cannot be removed. This never silently discards data."])
      (when (= type :trust)
        [:div.trust-warning [:h3 (:title document)]
         [:p "This file contains executable JavaScript. Values, formulas, and formatting rules run with access to this page, its storage, and the network."]
         [:p [:b "Only open documents from sources you trust."] " Import creates a new copy and never overwrites an existing table."]])
      (when (= type :delete) [:p "“" (:title document) "” will be permanently removed from this browser. Export a backup first."])
      (when error [:div.inline-error {:role "alert"} error])
      [:button.hidden-submit {:type "submit" :tab-index -1 :aria-hidden true} "Submit"]]
     [:button {:class (str "button " (if (= type :delete) "danger" "primary")) :on-click submit-dialog!
               :auto-focus (boolean (#{:delete :trust} type))}
      (case type :create "Create table" :rename "Save title" :dimensions "Apply" :trust "Trust & open" :delete "Delete table" "Apply")]
     #(swap! s/app assoc :dialog nil)]))

(defn app-view []
  (let [{:keys [route theme help doc editor dialog toast error]} @s/app]
    [:div.app {:data-theme theme}
     [topbar]
     (when error [:div.storage-error {:role "alert"} error [:button {:on-click #(swap! s/app assoc :error nil) :aria-label "Dismiss error"} "×"]])
     [:div.app-main {:inert (boolean (or editor (:rule-editor @s/app) dialog))}
      (when help [help-sidebar])
      [:div.workspace
       (case route :loading [:div.loading-state [icon :cube 42] [:p "Loading…"]]
             :home [home-page] :editor [editor-workspace])]]
     (when doc [:style (str "@scope (.sheet-scope) to (.out-of-bounds) {\n" (:css doc) "\n}")])
     (when editor [cell-editor])
     (when (:rule-editor @s/app) [rule-editor])
     (when dialog [generic-dialog])
     (when toast [:div.toast {:role "status"} [icon :check 16] toast])]))
