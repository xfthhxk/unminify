#!/usr/bin/env nbb
;; Adapted from https://github.com/mifi/stacktracify
(ns unminify
  "unminify: restore a minified stacktrace using a source map

  Usage:
  unminify.cljs :source-map index.js.map :stacktrace stacktrace.txt

  Available options:
  - :source-map (required) path to source map file
  - :stacktrace (required) path to a file with the minified stacktrace"
  (:require ["stacktrace-parser" :as stacktrace-parser]
            ["source-map" :as source-map]
            ["fs" :as fs]
            [clojure.edn :as edn]
            [promesa.core :as p]))

(defn unminify
  [{:keys [source-map-file stacktrace]}]
  (let [stack (->> (stacktrace-parser/parse stacktrace)
                   (mapv (fn [obj]
                           {:method-name (.-methodName obj)
                            :line-number (.-lineNumber obj)
                            :column (.-column obj)})))
        src-map (-> (fs/readFileSync source-map-file "utf-8")
                    (js/JSON.parse))
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

(defn exit!
  [status msg]
  (println msg)
  (js/process.exit status))

(defn validate-args!
  [{:keys [source-map stacktrace]}]
  (when-not source-map
    (exit! -1 ":source-map not specified"))
  (when-not stacktrace
    (exit! -1 ":stacktrace not specified"))
  (when-not (fs/existsSync source-map)
    (exit! -1 (str ":source-map file not found: " source-map)))
  (when-not (fs/existsSync stacktrace)
    (exit! -1 (str ":stacktrace file not found: " stacktrace))))

(defn- usage
  []
  (-> *ns* meta :doc))


(defn cli
  []
  (let [args-set (set *command-line-args*)]
    (when (or (empty? args-set)
              (contains? args-set "-h")
              (contains? args-set "--help"))
      (exit! -1 (usage)))
    (when-not (even? (count *command-line-args*))
      (exit! -1 "Expected even number of args"))
    (let [{:keys [source-map
                  stacktrace] :as args}
          (into {} (for [[k v] (partition 2 *command-line-args*)]
                     [(edn/read-string k) v]))]
      (validate-args! args)
      (p/let [stack (unminify {:source-map-file source-map
                               :stacktrace (fs/readFileSync stacktrace "utf-8")})]
        (-> stack
            stringify
            println)))))

(cli)
