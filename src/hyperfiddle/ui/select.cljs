(ns hyperfiddle.ui.select
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [contrib.data :refer [unqualify]]
   [contrib.datomic]
   [contrib.do :refer [from-result]]
   [contrib.ui :refer [debounced text easy-checkbox]]
   [hyperfiddle.api :as hf]
   [hypercrud.browser.base :as base]
   [hyperfiddle.ui.iframe :as iframe]
   [hyperfiddle.runtime :as runtime]
   [hypercrud.browser.context :as context]
   [hyperfiddle.data :as data]
   [hyperfiddle.ui.error :as ui-error]
   [hyperfiddle.ui.util :refer [with-entity-tx!]]
   ["react-bootstrap-typeahead" :refer [Typeahead AsyncTypeahead #_TypeaheadInputSingle]]))

(defn disabled? [ctx props]
  (or (boolean (:disabled props))
      (boolean (:read-only props))                          ; legacy
      (nil? (hf/e ctx))))

(defn context-of
  [ctx fiddle-ident]
  (let [link-ref (from-result (data/select+ ctx fiddle-ident))
        link-ctx (from-result (context/refocus-to-link+ ctx link-ref))
        [occluded-ctx initial-route] (from-result (context/build-route-and-occlude+ link-ctx link-ref)) ; ":link/fiddle required"
        options-pid (context/build-pid-from-link ctx link-ctx initial-route)
        ctx (from-result (base/browse-partition+ (context/set-partition occluded-ctx options-pid)))]
    ctx))

(defn resolve-options
  [ctx props]
  (condp some [(unqualify (contrib.datomic/parser-type (hf/qfind ctx)))]
         #{:find-coll :find-scalar} (hf/data ctx)
         #{:find-rel :find-tuple} (mapv #(get % (::hf/option-element props 0))
                                        (hf/data ctx))))

(defn options-of
  [ctx fiddle-ident props]
  (resolve-options (context-of ctx fiddle-ident) props))

(defn tag
  [ctx {:keys [value] :as props}]
  (let [[e a _] @(:hypercrud.browser/eav ctx)]
    [:div.tag props value
     [:button.tag.remove {:on-click #(with-entity-tx! ctx [[:db/retract e a value]])} "✖"]]))

(defn search
  [ctx {:keys [by of]}]
  (let [ctx (context-of ctx of)]
    [debounced
     {:placeholder "Type here to search ..."
      :value (get by @(-> ctx :hypercrud.browser/route) "")
      :on-change (fn [o n]
                   (try
                     (hf/swap-route! ctx
                                     assoc by n)
                     (catch js/Error e
                       (runtime/set-error runtime partition-id e))))}
     text]))

(defn search-select
  [ctx {:keys [by of] :as props}]
  (let [options (if (coll? of) of (options-of ctx of props))
        ctx (context-of ctx of)]
    [debounced
     {:placeholder "Type here to search ..."
      :value (get by @(-> ctx :hypercrud.browser/route) "")
      :on-change (fn [o n]
                   (try
                     (hf/swap-route! ctx
                                     assoc by n)
                     (catch js/Error e
                       (runtime/set-error runtime partition-id e))))}
     (fn []
       (into
        [:select {}]
        (map (fn [v] [:option {} v]) options)))]))

(defn ->selections
  [ctx props selections-comp selection-comp]
  (let [selected (context/data ctx)]
    (into [selections-comp {}]
          (map (fn [v] [selection-comp ctx {:value v} v]) selected))))

(defn ->options
  [ctx {:keys [options] :as props} options-comp option-comp]
  (let [selected (set (context/data ctx))
        options (if (coll? options) options (options-of ctx options props))]
    (into [options-comp ctx {}]
          (map
           (fn [value]

             [option-comp
              ctx
              {:value value
               :selected (contains? selected value)
               :on-change (fn [select?]
                            (apply
                              ((::hf/view-change! ctx) ctx)
                              (if select?
                                [selected (conj selected value)]
                                [selected (disj selected value)])))}])

           options))))


(defn select
  [ctx {:keys [options components]}]
  [:div
   (when (or (:options components) (:option components))
     [->options ctx {:options options} (:options components (fn [_ & args] (into [:div] args))) (:option components (fn [_ & args] (into [:div] args)))])
   (when (or (:selections components) (:selection components))
     [->selections ctx {} (:selections components :div) (:selection components :div)])])

(defn checkboxes
  [ctx props]
  (condp = (:db/cardinality (context/attr ctx))
    :db.cardinality/many
    [select ctx
      (assoc props
        :components
        {:option (fn [_ {:keys [value] :as props}]
                   [:div [easy-checkbox (assoc props :checked (:selected props)) value]])})]

    :db.cardinality/one
    [select ctx (assoc props :components
                       {:option
                        (fn [ctx props]
                          (let [[e a] @(:hypercrud.browser/eav ctx)]
                            [:div [contrib.ui/radio-with-label
                                   (merge
                                    {:name (str e a)}
                                    (select-keys props [:value]))]]))})]))

(defn- typeahead-error [{rt :runtime pid :partition-id :as ctx} e props]
  ; even if browsing fails, the user needs a chance to alter their search, so just add an ugly error message
  [:<>
   [:span (or (ex-message e) (str e))]           ; should use error-comp, wrong ctx in scope though
   [truncated-options 0]
   ^{:key :async-typeahead}
   [:> AsyncTypeahead props]])

(defn- truncated-options [n]
  ; todo this destroys table cells
  [:div.alert.alert-warning (str "Warning: Options resultset has been truncated to " hf/browser-query-limit " records. Please add additional filters")])

(defn- adapt-new-entry
  [entry]
  (if (and (object? entry) (.-customOption entry))
    (.-label entry) ; appears broken
    entry))

(defn- typeahead-success [ctx {:keys [selected multiple] :as props}]
  [:<>
   #_[truncated-options (count @(:hypercrud.browser/result ctx))]
   ^{:key :async-typeahead}
   [:> AsyncTypeahead
    (-> props
        (assoc :on-change (fn [jxs]
                            (let [selected (if multiple selected #{selected})
                                  current (array-seq jxs)
                                  [selected current] (if-let [ident-key (::hf/ident-key props (::hf/option-label props identity))]
                                                       [(map ident-key selected) (map ident-key current)]
                                                       [selected current])
                                  current (cond->> current
                                                   (:allow-new props)
                                                   (map adapt-new-entry))]
                              (if multiple
                                ((:on-change props) selected current)
                                ((:on-change props) (first selected) (first current))))))

        (assoc :label-key (comp str (::hf/option-label props identity)))

        (update :selected (fn [selected]
                            (if (:multiple props)
                              selected
                              #js [selected])))

        (update :options to-array))]])


(def default-props
  ; Rendering strategy that works in tables
  ; http://hyperfiddle.hyperfiddle.site/:hyperfiddle.ide!edit/(:fiddle!ident,:hyperfiddle!ide)
  {:position-fixed true
   :align :left
   :use-cache false
   :min-length 0})                                          ; minLength 0 shows the initial result page before having typed anything

(defn- select-needle-typeahead
  [ctx {:keys [multiple] :as props}]
  (let [{rt :runtime} ctx]
    [:div {:key (str (:partition-id ctx))}

     [iframe/stale-browse ctx
       typeahead-error
       typeahead-success
       (merge
         default-props
         {:id (:html/id props)
          :is-loading (runtime/loading? rt (:partition-id ctx))

          :filter-by (if multiple
                       (complement (partial contains? (set (:selected props))))
                       (constantly true))

          :on-search (fn [s]
                       (try
                         (hf/swap-route! ctx assoc (::hf/needle-key props) s)
                         (catch js/Error e
                           (runtime/set-error rt (:partition-id ctx) e))))

          :highlight-only-result (not (:allow-new props))} ; does not work with allowNew enabled, avoiding a warning

         props)]]))


(defn ^:export picklist "typeahead picker integrated with hyperfiddle IO, single- and multi-select"
  [ctx props]
  (assert (:options props) "select: :options prop is required")
  (assert (::hf/view-change! ctx))                        ; good: (f e a adds rets) (f ctx selection) bad: (f ctx os ns)
  (assert (::hf/needle-key props))
  (let [ref? (context/attr? ctx :db.type/ref)
        many? (context/attr? ctx :db.cardinality/many)
        options-ctx (context-of ctx (:options props))]
    (try
      [select-needle-typeahead options-ctx
       (merge
        {:selected (context/data ctx)
         :options (resolve-options options-ctx props)
         :multiple many?
         :on-change ((::hf/view-change! ctx) ctx)
         ::hf/ident-key (if ref?
                          (partial hf/id ctx)
                          identity)}
        (select-keys props [:html/id])
        (select-keys props [::hf/option-label ::hf/needle-key ::hf/ident-key]))])))

      ; (catch js/Error e
      ;   [(ui-error/error-comp ctx) e]))))
