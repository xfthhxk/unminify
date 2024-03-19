(ns unminify.gcp
  "GCP specific unminify and write to error reporting."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [goog.object]
            [cognitect.transit :as transit]
            ["@google-cloud/storage" :as storage]
            ["@google-cloud/error-reporting" :as er]
            ["express$default" :as express]
            ["cors$default" :as cors]
            [unminify.core :as unminify]
            [promesa.core :as p]
            ["process" :as process]
            ["path" :as path]
            ["os" :as os]
            ["fs" :as fs]))

(defonce ^:dynamic *state* {})

(defn- download!
  "Downloads a `filename` from `bucket` and writes it to `destination`.
  Returns a promise."
  [{:keys [bucket file destination]}]
  (-> (storage/Storage.)
      (.bucket bucket)
      (.file file)
      (.download #js {:destination destination})))

(defn- gen-destination
  "Returns the destination file to save the source map to"
  []
  (let [dir (-> (os/tmpdir)
                (path/join "unminify")
                (fs/mkdtempSync))]
    {:dir dir
     :file (path/join dir "source.js.map")}))

(defn- remote-filename
  "Returns a string with the version embedded
  into the filename template. the filename should
  be some path like string with '${version}' in
  the string ie 'source-maps/v${version}.js'"
  [filename version]
  (let [filename (if (str/starts-with? filename "/")
                   (subs filename 1)
                   filename)]
    (str/replace filename "${version}" version)))


(defn- send-to-error-reporting!
  "Repots an error via error reporting."
  [{:keys [headers service version user-id
           remote-ip]} stacktrace]
  (let [errors (er/ErrorReporting. #js {:reportMode "always"})
        error-msg (doto (.event errors)
                    (.setMessage stacktrace)
                    (.setReferrer (goog.object/get headers "user-agent"))
                    (.setRemoteIp (goog.object/get headers "x-forwarded-for" remote-ip))
                    (.setServiceContext service version)
                    (.setUser user-id)
                    (.setUserAgent (goog.object/get headers "user-agent")))]
    (.report errors error-msg)))

(defn report-unminified!
  "Unminifies the `stacktrace` and reports it to error reporting."
  [{:keys [stacktrace
           version
           service
           bucket
           filename
           debug?] :as args}]
  (when debug?
    (js/console.debug (pr-str args)))
  (assert version)
  (assert bucket)
  (assert stacktrace)
  (assert service)
  (assert filename)
  (let [remote-file (remote-filename filename version)
        {:keys [dir file]} (gen-destination)]
    (p/let [_ (download! {:bucket bucket
                          :file remote-file
                          :destination file})
            legible (unminify/unminify {:stacktrace stacktrace
                                        :source-map file})]
      (send-to-error-reporting! args legible)
      (fs/rm dir #js {:force true :recursive true} identity))))


;;----------------------------------------------------------------------
;; -- HTTP API --
;;----------------------------------------------------------------------
(defn env-variables
  []
  (let [e (.-env process)]
    {:port (js/parseInt (goog.object/get e "PORT" "8080"))
     :error-endpoint (goog.object/get e "ERROR_ENDPOINT" "/error")
     :ping-endpoint (goog.object/get e "PING_ENDPOINT")
     :bucket (goog.object/get e "BUCKET")
     :filename (goog.object/get e "FILENAME")
     :cors-origins (goog.object/get e "CORS_ORIGINS")
     :cors-exposed-headers (goog.object/get e "CORS_EXPOSED_HEADERS")
     :cors-allowed-headers (goog.object/get e "CORS_ALLOWED_HEADERS")
     :cors-credentials? (= "true" (goog.object/get e "CORS_CREDENTIALS" "false"))
     :cors-max-age (js/parseInt (goog.object/get e "CORS_MAX_AGE" "600"))
     :node-env (goog.object/get e "NODE_ENV")
     :debug? (= "true" (goog.object/get e "DEBUG" "false"))}))


(defn body-reader
  [req _res next]
  (let [buffers #js []]
    (.on req "data" #(.push buffers %))
    (.on req "end" (fn []
                     (->> buffers
                          js/Buffer.concat
                          str
                          (goog.object/set req "body"))
                     (next)))))


(defn- parse-transit
  [s]
  (transit/read (transit/reader :json) s))

(defn strip-byte-order-mark
  [s]
  (if (= \ufeff (first s))
    (subs s 1)
    s))

(defn- parse-json
  [s]
  (-> s js/JSON.parse (js->clj :keywordize-keys true)))

(defn body-parser
  [req _res next]
  (let [ct (.header req "content-type")
        body (some-> (.-body req) strip-byte-order-mark)
        parser (case ct
                 "application/json" parse-json
                 "application/edn" edn/read-string
                 "application/transit+json" parse-transit
                 identity)
        parsed (parser body)]
    (goog.object/set req "body" parsed)
    (next)))

(defn parse-cors-orgins
  [s]
  (if (str/blank? s)
    "*"
    (->> (str/split s ",")
         (mapv re-pattern)
         (into-array))))

(defn start-server!
  []
  (println "Starting server.")
  (let [{:keys [port
                ping-endpoint
                error-endpoint
                cors-origins
                cors-exposed-headers
                cors-allowed-headers
                cors-credentials?
                cors-max-age]} *state*
        app (express)
        cors-mw (-> {:origin (parse-cors-orgins cors-origins)
                     :methods ["GET" "POST"]
                     :allowedHeaders cors-allowed-headers
                     :exposedHeaders cors-exposed-headers
                     :credentials cors-credentials?
                     :maxAge cors-max-age
                     :optionsSuccessStatus 204}
                     clj->js
                     cors)]

    (doto app
      (.disable "x-powered-by")
      (.use body-reader)
      (.use body-parser)
      (.use cors-mw)
      (.options "*" cors-mw))

    (when ping-endpoint
      (.get app ping-endpoint
            (fn [_req res]
              (doto res
                (.json #js {"ping" "pong"})
                (.status 200)))))
    (.post app error-endpoint
           (fn [req res]
             (-> (.-body req)
                 (assoc :headers (.-headers req)
                        :remote-ip (-> req .-socket .-remoteAddress))
                 (merge (select-keys *state* [:bucket :filename :debug?]))
                 (report-unminified!)
                 (.then (fn [_]
                          (println 204 error-endpoint)
                          (doto res
                            (.status 204)
                            (.end))))
                 (.catch (fn [err]
                           (println 500 error-endpoint err)
                           (doto res
                             (.status 500)
                             (.end)))))))
    (.listen app port (fn [] (println "Server listening on port: " port)))))

(defn main
  []
  (.on js/process "unhandledRejection" (fn [reason _promise] (println "ERROR: unhandledRejection " reason)))
  (let [env-vars (env-variables)]
    (alter-var-root #'*state* (constantly env-vars))
    (println "state: " *state*)
    (assert (string? (:bucket *state*)) "BUCKET env not found")
    (assert (string? (:filename *state*)) "FILENAME env not found")
    (when-not (get env-vars :node-env)
      (println "NODE_ENV is not set, setting to 'production' for safety.")
      (goog.object/set (.-env process) "NODE_ENV" "production")
      (assoc *state* :node-env "production"))
    (start-server!)))

(main)
