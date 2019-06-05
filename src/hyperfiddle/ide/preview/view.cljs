(ns hyperfiddle.ide.preview.view
  (:require
    [cats.monad.either :as either]
    [contrib.cljs-platform :refer [code-for-browser]]
    [contrib.component :as component]
    [contrib.data :refer [map-values]]
    [contrib.local-storage :as local-storage]
    [contrib.reactive :as r]
    [contrib.ui]
    [hypercrud.browser.base :as base]
    [hyperfiddle.actions :as actions]
    [hyperfiddle.branch :as branch]
    [hyperfiddle.domain :as domain]
    [hyperfiddle.foundation :as foundation]
    [hyperfiddle.ide.domain :as ide-domain]
    [hyperfiddle.io.basis :as basis]
    [hyperfiddle.local-storage-sync :refer [map->LocalStorageSync]]
    [hyperfiddle.project :as project]
    [hyperfiddle.reducers :as reducers]
    [hyperfiddle.route :as route]
    [hyperfiddle.runtime :as runtime]
    [hyperfiddle.ui.iframe :refer [iframe-cmp]]
    [hyperfiddle.ui.loading :as loading]
    [re-com.core :as re-com]
    [reagent.core :as reagent]
    [promesa.core :as p]
    [taoensso.timbre :as timbre]))


(def keypress (code-for-browser (js/keypress.Listener. js/document.body)))

(defn frame-on-click [rt route event]
  (when (and route (.-altKey event))                        ; under what circumstances is route nil?
    (let [native-event (.-nativeEvent event)
          anchor (-> (.composedPath native-event) (aget 0) (.matches "a"))
          anchor-descendant (-> (.composedPath native-event) (aget 0) (.matches "a *"))]
      (when-not (or anchor anchor-descendant)
        (.stopPropagation event)
        (js/window.open (domain/url-encode (runtime/domain rt) route) "_blank")))))

(defn build-user-branch-id [ide-branch] (branch/child-branch-id ide-branch "user"))

(defn ide-branch-reference [rt ide-branch]
  (-> @(runtime/state rt [::runtime/partitions ide-branch])
      (select-keys [:stage :local-basis])))

(defn stale-global-basis? [rt]
  (= -1 (basis/compare-uri-maps @(runtime/state rt [::runtime/global-basis :user])
                                @(runtime/state rt [::ide-user-global-basis]))))

(defn stale-local-basis? [rt user-branch ide-branch]
  (= -1 (basis/compare-uri-maps
          @(runtime/state rt [::runtime/partitions user-branch :local-basis])
          @(runtime/state rt [::runtime/partitions ide-branch :local-basis]))))

(defn preview-stale? [preview-rt ide-branch preview-branch preview-state]
  #_(println (pr-str {:initial-render @(r/cursor preview-state [:initial-render])
                      :staleness-not= (not= @(r/cursor preview-state [:staleness]) (ide-branch-reference rt ide-branch))
                      :stale-gb? (stale-global-basis?)
                      :stale-lb (stale-local-basis?)}))
  (and (not @(r/cursor preview-state [:initial-render]))
       (or (not= @(r/cursor preview-state [:staleness]) (ide-branch-reference preview-rt ide-branch))
           (stale-global-basis? preview-rt)
           (stale-local-basis? preview-rt preview-branch ide-branch))))

(defn refresh! [preview-rt ide-branch preview-branch preview-state]
  (when-not (or @(r/cursor preview-state [:is-refreshing])
                @(r/cursor preview-state [:initial-render]))
    (let [init-level (cond
                       (stale-global-basis? preview-rt) actions/LEVEL-NONE
                       (stale-local-basis? preview-rt preview-branch ide-branch) actions/LEVEL-GLOBAL-BASIS
                       :else actions/LEVEL-LOCAL-BASIS)]
      (swap! preview-state assoc
             :staleness (ide-branch-reference preview-rt ide-branch)
             :is-refreshing true)
      (-> (actions/bootstrap-data preview-rt preview-branch init-level)
          (p/finally (fn [] (swap! preview-state assoc :is-refreshing false)))))))

(defn preview-toolbar [preview-rt ide-branch preview-branch preview-state]
  (let [stale @(r/track preview-stale? preview-rt ide-branch preview-branch preview-state)]
    [:span.preview-toolbar (when stale {:class "hyperfiddle-preview-stale"})
     ; todo animation
     (let [is-hovering-refresh-button (r/cursor preview-state [:is-hovering-refresh-button])]
       [:button {:class "refresh" :on-click #(refresh! preview-rt ide-branch preview-branch preview-state)
                 :disabled @(r/cursor preview-state [:is-refreshing])
                 :on-mouse-over #(do (reset! is-hovering-refresh-button true) nil)
                 :on-mouse-out #(do (reset! is-hovering-refresh-button false) nil)}
        [re-com/popover-tooltip
         :showing? (r/atom (and @is-hovering-refresh-button (not @(r/cursor preview-state [:is-refreshing]))))
         :anchor "↻"
         :label "Re-compute stale preview (alt+enter)"]])
     [:span.url]
     #_[contrib.ui/text
        {:value (domain/url-encode (runtime/domain rt) route)
         :disabled true
         :class "url"
         :on-change (fn [s] (println s))}]
     (into [:span]
           (->> [{:label "edn" :tooltip "What the API client sees" :value :hypercrud.browser.browser-ui/api}
                 {:label "data" :tooltip "Ignore :fiddle/renderer" :value :hypercrud.browser.browser-ui/xray}
                 {:label "view" :tooltip "Use :fiddle/renderer" :value :hypercrud.browser.browser-ui/user}]
                (map (fn [props]
                       [contrib.ui/radio-with-label
                        (assoc props
                          :checked (= (:value props) @(r/cursor preview-state [:display-mode]))
                          :on-change #(swap! preview-state assoc :display-mode %))]))))]))

(defn preview-content [ctx ide-branch preview-state route]
  (if @(r/cursor preview-state [:initial-render])
    [loading/page (runtime/domain (:peer ctx))]
    (let [code+ (project/eval-domain-code!+ @(runtime/state (:peer ctx) [::runtime/partitions (:branch ctx) :project :project/code]))
          is-stale @(r/track preview-stale? (:peer ctx) ide-branch (:branch ctx) preview-state)]
      [:<>
       (when (either/left? code+)
         (let [e @code+]
           (timbre/error e)
           (let [href (domain/url-encode (runtime/domain (:peer ctx)) [:hyperfiddle.ide/env])
                 message (or (some-> (ex-cause e) ex-message) (ex-message e))]
             [:h6 {:style {:text-align "center" :background-color "lightpink" :margin 0 :padding "0.5em 0"}}
              "Exception evaluating " [:a {:href href} [:code ":domain/code"]] ": " message])))
       [:div#root (merge {}
                         (when is-stale {:class "hyperfiddle-preview-stale"})
                         (when @(r/cursor preview-state [:alt-key-pressed]) {:style {:cursor "pointer"}}))
        [:style {:dangerouslySetInnerHTML {:__html @(runtime/state (:peer ctx) [::runtime/partitions (:branch ctx) :project :project/css])}}]
        ^{:key "user-iframe"}
        [iframe-cmp ctx
         {:route route
          :class (some-> @(r/cursor preview-state [:display-mode]) name (->> (str "display-mode-")))}]]])))

(defn- ctx->ls [ctx]
  (atom (map->LocalStorageSync {:rt (:peer ctx) :branch-id (:branch ctx) :ls-key :USER-STATE})))

(def preview-effects
  (reagent/create-class
    {:reagent-render
     (fn [ctx ide-branch preview-state route]
       [preview-content ctx ide-branch preview-state route])

     :component-did-mount
     (fn [this]
       (let [[_ ctx ide-branch preview-state route] (reagent/argv this)
             user-branch (:branch ctx)
             rt (:peer ctx)]
         (.register-many keypress (clj->js [{"keys" "alt"
                                             "prevent_repeat" true
                                             "on_keydown" #(swap! preview-state assoc :alt-key-pressed true)
                                             "on_keyup" #(swap! preview-state assoc :alt-key-pressed false)}
                                            {"keys" "alt enter"
                                             "on_keydown" #(refresh! rt ide-branch (:branch ctx) preview-state)}
                                            {"keys" "ctrl `"
                                             "on_keydown" (fn []
                                                            (swap! preview-state update :display-mode
                                                                   #(if (not= :hypercrud.browser.browser-ui/user %)
                                                                      :hypercrud.browser.browser-ui/user
                                                                      :hypercrud.browser.browser-ui/xray)))}]))
         (runtime/dispatch! rt [:partition-route user-branch route])
         (when local-storage/is-supported
           (swap! (ctx->ls ctx) component/start))
         (-> (actions/bootstrap-data rt user-branch actions/LEVEL-NONE)
             (p/finally (fn []
                          (swap! preview-state assoc
                                 :initial-render false
                                 :is-refreshing false))))))

     :component-did-update
     (fn [this [_ _ prev-route _]]
       (let [[_ ctx ide-branch preview-state next-route] (reagent/argv this)
             user-branch (:branch ctx)
             rt (:peer ctx)]
         (when-not (= prev-route next-route)
           (swap! preview-state assoc :is-refreshing true)
           (-> (runtime/set-route rt user-branch next-route)
               (p/finally (fn [] (swap! preview-state assoc
                                        :staleness (ide-branch-reference rt ide-branch)
                                        :is-refreshing false)))))))

     :component-will-unmount
     (fn [this]
       (let [[_ ctx ide-branch preview-state route] (reagent/argv this)]
         (when local-storage/is-supported
           (component/stop @(ctx->ls ctx))))
       (.reset keypress))
     }))

(defn- to [parent-state user-state]
  (let [user-state (-> (update user-state ::runtime/partitions dissoc foundation/root-branch) ; root branch is readonly to users
                       (dissoc ::ide-user-global-basis ::runtime/user-id)
                       (reducers/root-reducer nil))]
    (assoc parent-state ::runtime/user-state user-state)))

(defn- from [ide-domain ide-branch parent-state]
  (let [root-partition (-> (get-in parent-state [::runtime/partitions ide-branch])
                           (select-keys [:route :stage :local-basis])
                           (update :local-basis (fn [local-basis] (map-values #(get local-basis %) (::ide-domain/user-dbname->ide ide-domain))))
                           (update :stage (fn [stage] (map-values #(get stage %) (::ide-domain/user-dbname->ide ide-domain)))))]
    (-> (::runtime/user-state parent-state)
        (assoc ::ide-user-global-basis (map-values #(get-in parent-state [::runtime/global-basis :user %])
                                                   (::ide-domain/user-dbname->ide ide-domain))
               ::runtime/user-id (::runtime/user-id parent-state))
        (assoc-in [::runtime/partitions foundation/root-branch] root-partition)
        (update-in [::runtime/partitions (build-user-branch-id ide-branch)] #(or % {})) ; branch MUST exist in state
        (reducers/root-reducer nil))))

(defn compute-user-route [ide-route]
  (let [[_ [fiddle-lookup-ref & datomic-args] service-args encoded-fragment] ide-route]
    (route/canonicalize
      (base/legacy-lookup-ref->fiddle-ident fiddle-lookup-ref)
      (vec datomic-args)
      service-args
      (when-not (hyperfiddle.ide/parse-ide-fragment encoded-fragment)
        encoded-fragment))))
