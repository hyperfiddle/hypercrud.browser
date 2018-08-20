(ns hypercrud.browser.context
  (:require
    [contrib.data :refer [ancestry-common ancestry-divergence]]
    [contrib.reactive :as r]
    [hypercrud.browser.field :as field]
    [hyperfiddle.actions :as actions]
    [hyperfiddle.domain :as domain]
    [hyperfiddle.runtime :as runtime]))


(defn clean [ctx]
  ; why not code-database-uri and all the custom ui/render fns?
  (dissoc ctx
          :route                                            ; Route is unrelated to the hyper-control ontology
          :uri                                              ; todo deprecate
          :hypercrud.ui/error
          :hyperfiddle.ui/layout

          :hypercrud.browser/data
          :hypercrud.browser/fiddle
          :hypercrud.browser/field
          :hypercrud.browser/links
          :hypercrud.browser/parent
          :hypercrud.browser/path
          :hypercrud.browser/schemas))

(defn source-mode [ctx]
  (-> ctx
      (assoc :hyperfiddle.ui/iframe-on-click (r/constantly nil) ; disable alt-click
             :hypercrud.ui/display-mode (r/track identity :hypercrud.browser.browser-ui/user))
      (update :hypercrud.browser/domain
              (fn [domain]
                (update (:hypercrud.browser/source-domain ctx) :domain/databases
                        (fn [dbs]
                          (->> dbs
                               (remove #(= "$" (:domain.database/name %)))
                               (cons {:domain.database/name "$"
                                      :domain.database/record (:domain/fiddle-database domain)})
                               vec)))))))

(defn attribute-segment? [path-segment]
  (and (not (contains? #{:head :body} path-segment))        ; todo cannot conflict with :db/ident :head | :body
       (or (keyword? path-segment)
           (= '* path-segment))))

(defn find-element-segment? [path-segment]
  (integer? path-segment))

(defn segment-type [segment]
  (cond
    (attribute-segment? segment) :attribute
    (find-element-segment? segment) :element
    :else :naked))

(defn target-route [ctx] @(runtime/state (:peer ctx) [::runtime/partitions nil :route]))

(defn dbname [ctx] (some->> (:hypercrud.browser/field ctx) (r/fmap ::field/source-symbol) deref str))

(defn uri [ctx] (some-> (dbname ctx) (domain/dbname->uri (:hypercrud.browser/domain ctx))))

(defn with-tx! [ctx tx]
  (let [uri (uri ctx)
        _ (assert uri)                                      ; todo downstream action should be validating this
        invert-route (:hypercrud.browser/invert-route ctx)]
    (runtime/dispatch! (:peer ctx) (actions/with (:peer ctx) invert-route (:branch ctx) uri tx))))

(defn hydrate-attribute [ctx ident & ?more-path]
  (r/cursor (:hypercrud.browser/schemas ctx) (concat [(dbname ctx) ident] ?more-path)))

(defn- set-parent [ctx]
  (assoc ctx :hypercrud.browser/parent (dissoc ctx :hypercrud.browser/data)))

(defn- set-parent-data [ctx]
  (update ctx :hypercrud.browser/parent (fnil into {}) (select-keys ctx [:hypercrud.browser/data])))

(defn set-data-source [ctx]
  ; todo why cant internals get the uri at the last second
  (assoc ctx :uri (uri ctx)))

(defn body [ctx & [?data]]
  (let [cardinality @(r/fmap ::field/cardinality (:hypercrud.browser/field ctx))]
    (case cardinality
      :db.cardinality/one (assert (nil? ?data))
      :db.cardinality/many (do (assert ?data (str "`:body` is invalid directly on card/many (do you need a table wrap?). current path: " (pr-str (:hypercrud.browser/path ctx))))
                               (assert (r/reactive? ?data))))
    (let [new-ctx (-> ctx
                      (set-parent)
                      (set-parent-data)
                      (update :hypercrud.browser/path conj :body))]
      (case cardinality
        :db.cardinality/one new-ctx
        :db.cardinality/many
        (-> new-ctx
            (assoc :hypercrud.browser/data ?data)
            (update :hypercrud.browser/field (partial r/fmap (r/partial r/last-arg-first assoc ::field/cardinality :db.cardinality/one))))))))

(letfn [(find-child-field [path-segment field]
          ; find-child-field is silly;  we already map over the fields to determine which paths to focus...
          (->> (::field/children field)
               (filter #(= (::field/path-segment %) path-segment))
               first))
        (focus-segment [ctx path-segment]
          (case path-segment
            ; todo cannot conflict with :db/ident :head
            :head
            (-> ctx
                (set-parent)
                (set-parent-data)
                (update :hypercrud.browser/path conj :head)
                (dissoc :hypercrud.browser/data))

            ; todo cannot conflict with :db/ident :body
            :body (body ctx)

            ; attribute or fe segment
            (let [field (r/fmap (r/partial find-child-field path-segment) (:hypercrud.browser/field ctx))
                  ctx (-> ctx
                          (set-parent)
                          (update :hypercrud.browser/path conj path-segment)
                          (assoc :hypercrud.browser/field field)
                          (set-data-source))]
              (cond-> ctx
                ; if we are in a head, we dont have data to set
                (not (some #{:head} (:hypercrud.browser/path ctx)))
                (-> (set-parent-data)
                    (assoc :hypercrud.browser/data
                           (let [f (r/fmap ::field/get-value field)]
                             (assert @f (str "focusing on a non-pulled attribute: " (pr-str (:hypercrud.browser/path ctx)) "."))
                             (r/fapply f (:hypercrud.browser/data ctx)))))))))]
  (defn focus [ctx relative-path]
    (reduce focus-segment ctx relative-path)))

(defn refocus "focus common ancestor" [ctx path]
  {:pre [ctx] :post [%]}
  (let [current-path (:hypercrud.browser/path ctx)
        common-ancestor-path (ancestry-common current-path path)
        unwind-offset (- (count current-path) (count common-ancestor-path))
        common-ancestor-ctx ((apply comp (repeat unwind-offset :hypercrud.browser/parent)) ctx)]
    (focus common-ancestor-ctx (ancestry-divergence path current-path))))
