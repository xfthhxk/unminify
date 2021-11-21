(ns unminify.cli
  "unminify: restore a minified stacktrace using a source map

  Usage:
  unminify :source-map index.js.map :stacktrace stacktrace.txt

  Available options:
  - :source-map (required) path to source map file
  - :stacktrace (required) path to a file with the minified stacktrace"
  (:require [clojure.edn :as edn]
            [unminify.core :as unminify]
            [promesa.core :as p]
            ["fs" :as fs]))

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
      (p/let [s (unminify/unminify
                 {:source-map source-map
                  :stacktrace (fs/readFileSync stacktrace "utf-8")})]
        (println s)))))

(alter-var-root #'*command-line-args*
                (-> js/process.argv
                    (.slice 2)
                    js->clj
                    not-empty
                    constantly))

(cli)
