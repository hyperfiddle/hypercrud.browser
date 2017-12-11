(ns hypercrud.browser.auto-anchor-formula
  (:require-macros [hypercrud.util.template :as template])
  (:require [cats.monad.either :as either]
            [hypercrud.client.core :as hc]
            [hypercrud.types.Entity :refer [->Entity Entity]]
            [hypercrud.types.ThinEntity :refer [ThinEntity]]
            [hypercrud.util.core :as util]
            [hypercrud.util.vedn :as vedn]
            [hypercrud.util.string :as hc-string]
            [taoensso.timbre :as timbre]))


(defn auto-entity-from-stage [ctx]
  ; This returns a new value each time the transaction changes - can't call it again later.
  ; So tx-fns must inspect the modal-route, they can't re-create the dbid.
  (let [dbval (hc/db (:peer ctx) (:uri ctx) (:branch ctx))
        id (-> (:branch dbval) js/Math.abs - str)]
    (->Entity dbval {:db/id id})))

; todo there are collisions when two anchors share the same 'location'
(defn deterministic-ident
  ([ctx]
   (deterministic-ident
     (:find-element ctx)
     (:cell-data ctx)
     (:attribute ctx)
     (:value ctx)))
  ([fe cell-data a v]
    ; Need comment explaining why.
    ; [fe e a v] quad is sufficient to answer "where are we".
    ; Why Db is omitted?
    ; Why value is only inspected in :many for unique hashing?
   (-> (str (:name fe) "."
            (or (:db/id cell-data) (hash cell-data)) "."
            (-> a :db/ident) "."
            (case (get-in a [:db/cardinality :db/ident])
              :db.cardinality/one nil
              :db.cardinality/many (hash (into #{} (mapv :db/id v))) ; todo scalar
              nil nil #_":db/id has a faked attribute with no cardinality, need more thought to make elegant"))
       hash js/Math.abs - str)))

(defn auto-entity [ctx]
  (let [cell-data (:cell-data ctx)
        dbval (if (instance? Entity cell-data)
                (.-dbval cell-data)
                (hc/db (:peer ctx) (:uri ctx) (:branch ctx)))]
    (->Entity dbval {:db/id (deterministic-ident ctx)})))

(def auto-formula-lookup
  (let [fe-no-create (->> (template/load-resource "auto-formula/fe-no-create.vedn")
                          (vedn/read-string)
                          (util/map-keys #(assoc % :fe true :c? false)))
        fe-create (->> (template/load-resource "auto-formula/fe-create.vedn")
                       (vedn/read-string)
                       (util/map-keys #(assoc % :fe true :c? true)))
        ; no fe = index or relation links
        no-fe {{:fe false :c? false :d? true :a false} nil
               {:fe false :c? false :d? true :a true} nil
               {:fe false :c? false :d? false :a false} nil
               {:fe false :c? false :d? false :a true} nil

               {:fe false :c? true :d? true :a false} nil
               {:fe false :c? true :d? true :a true} nil
               {:fe false :c? true :d? false :a false} nil
               {:fe false :c? true :d? false :a true} nil}]
    (merge fe-create fe-no-create no-fe)))

(defn auto-formula [anchor]
  (-> (hc-string/memoized-safe-read-edn-string (str "[" (:link/path anchor) "]"))
      (either/branch
        (fn [e]
          (timbre/error e)
          nil)
        (fn [path]
          (get auto-formula-lookup
               {:fe (not (nil? (first path)))
                :c? (or (:anchor/create? anchor) false)
                :d? (or (:anchor/repeating? anchor) false)
                :a (not (nil? (second path)))})))))
