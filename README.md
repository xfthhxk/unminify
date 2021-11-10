# unminify
unminifies JS stacktrace errors

* Command Line Interface (CLI)
* Google Cloud native Docker container (alpha)
* Other containers?

## Prerequisites
Install [nbb](https://github.com/babashka/nbb)
```shell
npm install nbb -g
```

## CLI Usage
NB. must be run from the project root for now.
```shell
# usage
./unminify.cljs
unminify: restore a minified stacktrace using a source map

  Usage:
  unminify.cljs :source-map index.js.map :stacktrace stacktrace.txt

  Available options:
  - :source-map (required) path to source map file
  - :stacktrace (required) path to a file with the minified stacktrace
```

Example minified stacktrace:
```shell
cat stacktrace.txt
TypeError: Cannot read properties of null (reading 'g')
    at https://app.example.com/js/index.js:933:67
    at tg (https://app.example.com/js/index.js:813:394)
    at Function.bd.j (https://app.example.com/js/index.js:815:147)
    at Function.ri.gb (https://app.example.com/js/index.js:933:34)
    at https://app.example.com/js/index.js:3201:443
    at https://app.example.com/js/index.js:1785:34
    at i_ (https://app.example.com/js/index.js:1727:67)
    at p_ (https://app.example.com/js/index.js:1741:67)
    at o_ (https://app.example.com/js/index.js:1745:415)
    at b (https://app.example.com/js/index.js:1724:150)
```

Unminify:
```shell
./unminify.cljs :source-map index.js.map :stacktrace stacktrace.txt
TypeError: Cannot read properties of null (reading 'g')
    at f (cljs/core.cljs:5265:37)
    at G__42694 (cljs/core.cljs:2527:20)
    at cljs.core.iter_reduce (cljs/core.cljs:2577:8)
    at cljs.core.reduce (cljs/core.cljs:5265:10)
    at page (example/app/core.cljs:55:49)
    at G__45346 (re_frame/subs.cljc:198:70)
    at f (reagent/ratom.cljs:44:5)
    at reagent.ratom/deref-capture (reagent/ratom.cljs:420:7)
    at _run (reagent/ratom.cljs:415:6)
    at reagent.impl.batching/ratom-flush (reagent/impl/batching.cljs:97:5)
```

## GCP Container
A Docker container to ingest errors, unminify and write to Error Reporting.

```shell
docker run -it --rm -p 8080:8080 \
--env BUCKET=some-bucket \
--env FILENAME='path/to/source-maps/${version}.index.js.map' \
--env ERROR_ENDPOINT=/error \
--env CORS_ORIGINS='https://*.example.com$,https://*.example.io$' \
--env CORS_CREDENTIALS=true \
--env CORS_MAX_AGE=600 \
--env CORS_ALLOWED_HEADERS='Access-Control-Allow-Origin, Access-Control-Request-Method' \
xfthhxk/unminify:gcp-alpha
```

* Uses source maps from Cloud Storage
* Unminifies the error
* Writes to Error Reporting
* Use environment variables to configure

The `FILENAME` contains `${version}` which is replaced with the version from the client to find the relevant source map. This means as part of your build, the source map must be copied to the bucket at the specified path.

The docker

* Client:
  - Sends request body as either JSON or EDN (Transit coming soon).
  - Includes `stacktrace`, `version` and `service` keys in body.



## Built With
* [nbb](https://github.com/babashka/nbb)


## Credits
* [Michiel Borkent](https://github.com/borkdude) for making nbb and other amazing tools so that I can Clojure all the things. Much joy!
* [mifi](https://github.com/mifi) for [stacktracify](https://github.com/mifi/stacktracify)

## License
This is free and unencumbered software released into the public domain.
