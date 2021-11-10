(ns unminify.core
  (:require ["stacktrace-parser" :as stacktrace-parser]
            ["source-map" :as source-map]
            ["fs" :as fs]
            [promesa.core :as p]))

(defn unminify*
  [{:keys [source-map stacktrace]}]
  (when-not (fs/existsSync source-map)
    (throw (ex-info "source-map file not found" {:source-map source-map})))
  (let [stack (->> (stacktrace-parser/parse stacktrace)
                   (mapv (fn [obj]
                           {:method-name (.-methodName obj)
                            :line-number (.-lineNumber obj)
                            :column (.-column obj)})))
        src-map (fs/readFileSync source-map "utf-8")
        header (first (.split stacktrace "\n"))]
    (p/let [consumer (source-map/SourceMapConsumer. src-map)]
      (reduce (fn [ans {:keys [method-name line-number column]
                       :or {method-name ""}}]
                (if (or (not line-number) (< line-number 1))
                  (conj ans {:method-name method-name})
                  (let [p (.originalPositionFor
                           consumer #js {:line line-number :column column})
                        name (some-> p .-name)
                        source (some-> p .-source)
                        line (some-> p .-line)
                        column (some-> p .-column)]
                    (cond-> ans
                      (and p line) (conj {:method-name name
                                          :source source
                                          :line line
                                          :column column})))))
              [{:header header}]
              stack))))

(defn stringify
  [[header & stack]]
  (reduce (fn [ans {:keys [method-name source line column]}]
            (if line
              (str ans "    at " method-name " (" source ":" line ":" column ")\n")
              (str ans "    at " method-name "\n")))
          (some-> header :header (str "\n"))
          stack))

(defn unminify
  "unminifies and stringifies the `:stacktrace` using `:source-map` a
  string pointing to a source map file."
  [args]
  (p/let [stack (unminify* args)]
    (stringify stack)))
