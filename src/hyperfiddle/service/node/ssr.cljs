(ns hyperfiddle.service.node.ssr
  (:require [cats.core :refer [mlet return]]
            [cljs.nodejs :as node]
            [hypercrud.client.core :as hc]
            [hypercrud.client.peer :as peer]
            [hypercrud.transit :as transit]
            [hypercrud.util.core :as util :refer [unwrap]]
            [hypercrud.util.performance :as perf]
            [hypercrud.util.reactive :as reactive]
            [hypercrud.util.template :as template]
            [hyperfiddle.appval.state.reducers :as reducers]
            [hyperfiddle.foundation :as foundation]
            [hyperfiddle.ide :as ide]
            [hyperfiddle.io.global-basis :refer [global-basis-rpc!]]
            [hyperfiddle.io.hydrate-requests :refer [hydrate-requests-rpc!]]
            [hyperfiddle.io.hydrate-route :refer [hydrate-route-rpc!]]
            [hyperfiddle.io.local-basis :refer [fetch-domain!]]
            [hyperfiddle.io.sync :refer [sync-rpc!]]
            [hyperfiddle.runtime :as runtime]
            [hyperfiddle.service.node.lib :as lib :refer [req->service-uri]]
            [hyperfiddle.state :as state]
            [promesa.core :as p]
            [reagent.dom.server :as reagent-server]
            [taoensso.timbre :as timbre]))

(def cheerio (node/require "cheerio"))


(defn render-local-html [F]                                 ; react 16 is async, and this can fail
  ; html fragment, not a document, no <html> enclosing tag
  (p/resolved
    (perf/time (fn [get-total-time] (timbre/debug "Render total time:" (get-total-time)))
               (reagent-server/render-to-string (F)))))

(def analytics (template/load-resource "analytics.html"))

(def template
  "
  <!DOCTYPE html>
  <html lang='en'>
  <head>
    <title></title>
    <link id='app-css' rel='stylesheet' type='text/css'>
    <meta name='viewport' content='width=device-width,initial-scale=1'>
    <meta charset='UTF-8'>
    <script id='build' type='text/edn'></script>
  </head>
  <body>
    <div id='root'></div>
    <script id='params' type='text/edn'></script>
    <script id='state' type='text/edn'></script>
    <script id='preamble' type='text/javascript'></script>
    <script id='main' type='text/javascript'></script>
  </body>
  </html>")

(defn evaluated-template [env state-val params serve-js? app-html]
  (let [$ (.load cheerio template)
        resource-base (str (:STATIC_RESOURCES env) "/" (:BUILD env))]
    (-> ($ "title") (.text "Hyperfiddle"))
    (-> ($ "#app-css") (.attr "href" (str resource-base "/styles.css")))
    (-> ($ "#root") (.html app-html))
    (when serve-js?
      (-> ($ "#params") (.text (transit/encode params)))    ; env vars for client side rendering
      (-> ($ "#state") (.text (transit/encode state-val)))
      (-> ($ "#preamble") (.attr "src" (str resource-base "/preamble.js")))
      (-> ($ "#main") (.attr "src" (str resource-base "/main.js"))))
    (-> ($ "#build") (.text (:BUILD env)))
    (when (:ANALYTICS env) (-> ($ "#root") (.after analytics))) ; if logged in, issue identify?
    (.html $)))

(defn html [env state-val serve-js? app-html]
  (let [params {:hyperfiddle-hostname (:HF_HOSTNAME env)}]
    (perf/time (fn [get-total-time] (timbre/debug "Template total time:" (get-total-time)))
               (evaluated-template env state-val params serve-js? app-html))))

(defn ssr [env rt hostname]
  (let [state-atom (.-state-atom rt)
        get-state (fn [] @state-atom)
        dispatch! (state/build-dispatch state-atom reducers/root-reducer)
        aliased? (foundation/alias? (foundation/hostname->hf-domain-name hostname (:HF_HOSTNAME env)))
        serve-js? true #_(not aliased?)
        get-bootstrap-state (if aliased?
                              (fn [] @state-atom)
                              (constantly @state-atom))
        load-level foundation/LEVEL-HYDRATE-PAGE]
    (-> (foundation/bootstrap-data rt dispatch! get-state foundation/LEVEL-NONE load-level)
        (p/catch (constantly (p/resolved nil)))             ; any error above IS NOT fatal, so render the UI. anything below IS fatal
        (p/then #(runtime/ssr rt (:encoded-route @state-atom)))
        (p/then (fn [html-fragment] (html env (get-bootstrap-state) serve-js? html-fragment)))
        (p/catch (fn [error]
                   (timbre/error error)
                   (let [html-fragment (str "<h2>Error:</h2><pre>" (util/pprint-str error 100) "</pre>")]
                     ; careful this needs to throw a Throwable in clj
                     (p/rejected (html env (get-bootstrap-state) serve-js? html-fragment))))))))

(deftype IdeSsrRuntime [hyperfiddle-hostname hostname foo target-repo service-uri state-atom]
  runtime/AppFnGlobalBasis
  (global-basis [rt]
    (global-basis-rpc! service-uri))

  runtime/AppValLocalBasis
  (local-basis-page [rt global-basis encoded-route]
    {:pre [(= "page" foo)]}
    (mlet [domain (fetch-domain! rt hostname hyperfiddle-hostname global-basis)]
      (return
        (let [ctx {:hostname hostname
                   :hyperfiddle-hostname hyperfiddle-hostname
                   :branch nil
                   :peer rt}]
          (foundation/local-basis "page" global-basis encoded-route domain ctx
                                  (partial ide/local-basis "page"))))))

  runtime/AppValHydrate
  (hydrate-route [rt local-basis encoded-route branch stage]
    ; If IDE, send up target-repo as well (encoded in route as query param?)
    (hydrate-route-rpc! service-uri local-basis encoded-route foo target-repo branch stage))

  (hydrate-route-page [rt local-basis encoded-route stage]
    (hydrate-route-rpc! service-uri local-basis encoded-route "page" nil nil stage))

  runtime/AppFnHydrate
  (hydrate-requests [rt local-basis stage requests]
    (hydrate-requests-rpc! service-uri local-basis stage requests))

  runtime/AppFnSync
  (sync [rt dbs]
    (sync-rpc! service-uri dbs))

  runtime/AppFnRenderPageRoot
  (ssr [rt-page route]
    (assert (= foo "page") "Impossible; sub-rts don't render, they just hydrate")
    ; We have the domain if we are here.
    ; This runtime doesn't actually support :domain/view-fn, we assume the foo interface.
    (let [ctx {:hostname hostname
               :hyperfiddle-hostname hyperfiddle-hostname
               :peer rt-page}]
      (render-local-html
        (partial foundation/view foo route ctx
                 (partial ide/view foo)))))

  hc/Peer
  (hydrate [this request]
    (peer/hydrate state-atom request))

  (db [this uri branch]
    (peer/db-pointer state-atom uri branch))

  hc/HydrateApi
  (hydrate-api [this request]
    (unwrap @(hc/hydrate this request)))

  ide/SplitRuntime
  (sub-rt [rt foo target-repo]
    (IdeSsrRuntime. hyperfiddle-hostname hostname foo target-repo service-uri state-atom))
  (target-repo [rt] target-repo)

  IHash
  (-hash [this] (goog/getUid this)))

(defn http-edge [env req res path-params query-params]
  (let [hostname (.-hostname req)
        state-val (-> {:encoded-route (.-path req)
                       :user-profile (lib/req->user-profile env req)}
                      (reducers/root-reducer nil))
        rt (IdeSsrRuntime. (:HF_HOSTNAME env) hostname "page" nil
                           (req->service-uri env req) (reactive/atom state-val))]
    ; Do not inject user-api-fn/view - it is baked into SsrRuntime
    (-> (ssr env rt hostname)
        (p/then (fn [html-resp]
                  (doto res
                    (.append "Cache-Control" "max-age=0")
                    (.status 200)
                    (.format #js {"text/html" #(.send res html-resp)}))))
        (p/catch (fn [error-resp]
                   (doto res
                     (.status 500)
                     (.format #js {"text/html" #(.send res error-resp)})))))))
