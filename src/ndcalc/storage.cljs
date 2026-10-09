(ns ndcalc.storage
  (:require [ndcalc.engine :as engine]))

(defonce connection (atom nil))

(defn open! []
  (js/Promise.
    (fn [resolve reject]
      (if-not (exists? js/indexedDB)
        (reject (js/Error. "IndexedDB is unavailable. Enable browser storage to save tables."))
        (let [request (.open js/indexedDB "ndcalc" 1)]
          (set! (.-onupgradeneeded request)
                (fn [_]
                  (let [db (.-result request)]
                    (.createObjectStore db "documents" #js {:keyPath "id"})
                    (.createObjectStore db "settings"))))
          (set! (.-onsuccess request)
                (fn [_]
                  (let [db (.-result request)]
                    (reset! connection db)
                    (set! (.-onversionchange db) (fn [_] (.close db) (reset! connection nil)))
                    (resolve db))))
          (set! (.-onerror request) (fn [_] (reject (.-error request))))
          (set! (.-onblocked request) (fn [_] (reject (js/Error. "Database upgrade blocked by another tab.")))))))))

(defn transact! [store mode operation]
  (js/Promise.
    (fn [resolve reject]
      (try
        (when-not @connection (throw (js/Error. "Database is not open.")))
        (let [tx (.transaction @connection #js [store] mode)
              request (operation (.objectStore tx store))]
          ;; Resolve only once the transaction commits, not on request success.
          (set! (.-oncomplete tx) (fn [_] (resolve (.-result request))))
          (set! (.-onabort tx) (fn [_] (reject (or (.-error tx) (js/Error. "Storage transaction aborted.")))))
          (set! (.-onerror tx) (fn [_] (reject (.-error tx)))))
        (catch :default e (reject e))))))

(defn save-document! [doc]
  (transact! "documents" "readwrite"
             #(.put % #js {:id (:id doc) :json (engine/document->json doc)})))

(defn all-documents! []
  (.then (transact! "documents" "readonly" #(.getAll %))
         (fn [records]
           (->> (array-seq records)
                (map (fn [record]
                       ;; Loading metadata must not execute expressions or assign new IDs.
                       (let [raw (js/JSON.parse (.-json record))
                             doc (js->clj raw :keywordize-keys true)]
                         (assoc doc
                                :aliases (engine/decode-aliases (.-aliases raw))
                                :cell-widths (or (engine/js-dictionary->map (aget raw "cell-widths")) {})
                                :hyperplanes (into {} (map (fn [k] [k (js->clj (aget (.-hyperplanes raw) k) :keywordize-keys true)])
                                                           (if (.-hyperplanes raw) (js/Object.keys (.-hyperplanes raw)) #js [])))
                                :cells (into {} (map (fn [[k v]] [k (js->clj v :keywordize-keys true)])
                                                     (map (fn [k] [k (aget (.-cells raw) k)])
                                                          (js/Object.keys (.-cells raw)))))
                                :named (into {} (map (fn [[k v]] [k (js->clj v :keywordize-keys true)])
                                                     (map (fn [k] [k (aget (.-named raw) k)])
                                                          (js/Object.keys (.-named raw)))))))))
                (sort-by :updatedAt >) vec))))

(defn delete-document! [id] (transact! "documents" "readwrite" #(.delete % id)))
(defn preferences! []
  (.then (transact! "settings" "readonly" #(.get % "preferences"))
         #(when % (js->clj % :keywordize-keys true))))
(defn save-preferences! [prefs]
  (transact! "settings" "readwrite" #(.put % (clj->js prefs) "preferences")))
