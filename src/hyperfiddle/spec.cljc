(ns hyperfiddle.spec
  (:require [clojure.spec.alpha :as s]
            [contrib.data :as data]
            [hyperfiddle.spec.parser :as parser]
            [hyperfiddle.spec.serializer :as serializer])
  #?(:cljs (:require-macros [clojure.spec.alpha :as s])))




(def parse parser/parse)
(def defs (comp reverse first serializer/serialize))
(def form (comp second serializer/serialize))

(defn leaf? [node]
  (empty? (:children node)))

(def branch? (complement leaf?))

(defn index
  "Index a spec tree by spec name. Useful for direct access."
  [tree]
  (data/index-by :name (tree-seq branch? :children tree)))

(defn fiddle-spec
  "Extract important info from a ::fn spec so they can be stored in a fiddle."
  [{:keys [type args ret] :as fspec}]
  (if (not= :hyperfiddle.spec/fn type)
    (throw (ex-info "A fiddle spec must be built from a function spec." {:type type}))
    fspec))

(defn ctx->spec
  "Get fiddle spec in context, if any"
  [ctx]
  (:fiddle/spec @(:hypercrud.browser/fiddle ctx)))

(defn fdef? [?spec]
  (and #?(:clj  (instance? clojure.lang.ILookup ?spec)
          :cljs (satisfies? cljs.core.ILookup ?spec))
       (contains? ?spec :args)
       (contains? ?spec :ret)
       (contains? ?spec :fn)))

(defn names [spec]
  (case (:type spec)
    :hyperfiddle.spec/keys
    (->> spec :children (map :name))
    :hyperfiddle.spec/cat
    (:names spec)
    :hyperfiddle.spec/alt
    (reduce (fn [acc names] (assoc acc (count names) names))
      {}
      (map names (:children spec)))))

(defn spec-keys [spec]
  (case (:type spec)
    :hyperfiddle.spec/keys
    (:keys spec)
    :hyperfiddle.spec/cat
    (:names spec)
    :hyperfiddle.spec/alt
    (->> (names spec)
         (mapcat val)
         (distinct))))

(defn arg?
  "State if `attribute` belongs to `spec` :args"
  [spec attr]
  (when-let [args (:args spec)]
    (contains? (set (spec-keys args)) attr)))

(defn args-spec [fspec]
  (if (qualified-symbol? fspec)
    (when (s/get-spec fspec)
      (args-spec (parse fspec)))
    (if (= ::fn (:type fspec))
      (:args fspec)
      (throw (ex-info "This spec is not a function spec, cannot extract argument spec from it." {:fspec fspec})))))

(defn args [ctx]
  (when-let [spec (ctx->spec ctx)]
    (and (fdef? spec)
         (:args spec))))

(defn best-match-for
  [arities args]
  (let [arity (count args)]
    (or (get arities arity)
        (reduce (fn [_ [proposed-arity names]]
                  (when (<= proposed-arity arity) ; works because sorted
                    (reduced names)))
                nil
                (sort-by first > arities)))))

(defn- composite?
  "State if a spec defines a collection"
  [x]
  (or (not (leaf? x))
      (#{`map? `set? `vector? `list? `seq?} (:predicate x))))

(defn shape [fspec]
  (when-let [ret (:ret fspec)]
    (when-let [type (:type ret)]
      (cond
        (#{::coll} type) (if (composite? (first (:children ret)))
                           '[:find [(pull $ ?e [*]) ...] :in $ :where [$ ?e]] ; TODO not support yet as ?e doesn't have a source
                           '[:find [?e ...] :in $ :where [$ ?e]])
        (#{::keys} type) '[:find (pull $ ?e [*]) . :in $ :where [$ ?e]]
        :else            '[:find ?e . :in $ :where [$ ?e]])))) ; TODO not support yet as ?e doesn't have a source


(defn identifier-impl
  "Do not call this directly, use 'identifier'"
  [form pred gfn]
  (let [spec (delay (s/specize* pred form))]
    (reify
      s/Specize
      (specize* [s] s)
      (specize* [s _] s)

      s/Spec
      (conform* [_ x] (s/conform* @spec x))
      (unform* [_ x] (s/unform* @spec x))
      (explain* [_ path via in x] (s/explain* @spec path via in x))
      (gen* [_ overrides path rmap] (s/gen* spec overrides path rmap))
      (with-gen* [_ gfn] (identifier-impl form pred gfn))
      (describe* [_]
        #?(:clj  `(hyperfiddle.spec/identifier ~(@#'clojure.spec.alpha/res form))
           :cljs `(identifier ~(s/mres form)))))))

(defmacro identifier
  "returns a spec that accepts nil and values satisfying pred"
  [pred]
  (let [pf #?(:clj  (@#'clojure.spec.alpha/res pred)
              :cljs (cljs.spec.alpha/res &env pred))]
    `(identifier-impl '~pf ~pred nil)))
