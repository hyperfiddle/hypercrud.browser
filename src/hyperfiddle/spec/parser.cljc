(ns hyperfiddle.spec.parser
  (:require [clojure.spec.alpha :as s]))

(defn spec-name [spec]
  (::s/name (meta spec)))

(defmulti parse-spec first)

(defn parse
  "Parse a potentially nested spec to a tree structure where nodes might have
  names and leaves are predicates.
  TODO: support recursive specs
  TODO: support s/or and s/and"
  [spec]
  (when spec
    (if (s/spec? spec)
      (parse-spec (list `s/def (spec-name spec) (s/form spec)))
      (if-let [spec (s/get-spec spec)]
        (parse spec)
        (if (seq? spec)
          (parse-spec spec)
          (parse-spec (list spec)))))))

(defmethod parse-spec `s/def [[_ name value]]
  (merge {:name name}
         (parse value)))

(defmethod parse-spec `s/fspec [[_ & args-seq]]
  (let [{:keys [args ret]} args-seq]
    {:type     :fn
     :args-seq args-seq
     :args     (some-> args parse)
     :ret      (some-> ret parse)}))

(defmethod parse-spec `s/coll-of [[_ name & args-seq]]
  (let [{:keys [] :as args-map} args-seq]
    {:type     :coll
     :args     args-map
     :args-seq args-seq
     :children [(parse name)]}))

(defmethod parse-spec `s/keys [[_ & {:keys [req req-un opt opt-un] :as args}]]
  (let [keys (set (concat req req-un opt opt-un))]
    {:type     :keys
     :keys     keys
     :args     args
     :children (mapv parse keys)}))

(defmethod parse-spec :default [form]
  {:type      :predicate
   :predicate (first form)})

