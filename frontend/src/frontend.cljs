(ns frontend
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs-http.client :as http]
            [cljs.core.async :refer [<! >! chan timeout] :as a]
            [cljs.pprint :as pp]

            [reagent.dom :as reagent.dom]
            [reagent.core :as reagent]
            [bide.core :as bide]
            [garden.core :as garden]
            [clojure.string :as s]
            [haslett.client :as ws]

            ["react" :as react]
            ["@mui/material" :as mui]
            ["@primer/octicons-react" :as octo]
            [reagent.impl.template :as rtpl]))

(def adapt reagent/adapt-react-class)

(set! *warn-on-infer* true)

(defn log [& args]
  (apply js/console.log (map clj->js args)))

(defn event-key ^str [^js/KeyboardEvent e]
  (.-key e))

(defn focus [^js/Object e]
  (.focus e))

(defn query-selector ^js/Object [^js/Object e ^str q]
  (.querySelector e q))

(defn blur-active []
  (.blur (.-activeElement js/document)))

(defn target-value [^js/Event e]
  (.-value (.-target e)))

(defn prevent-default [^js/Event e]
  (.preventDefault e))

(let [id (atom 0)
      -gen-id (memoize
               (fn [& args]
                 (swap! id inc)))]
  (defn gen-id [& args]
    (if (zero? (count args))
      (swap! id inc)
      (apply -gen-id args))))

(defonce state
  (reagent/atom
   {:modal-open false
    :search-focus false
    :key-listener false
    :mouse-listener false
    :search-text ""
    :page nil
    :time nil}))

(def style
  (garden/css
   [:body {:background-color "rgb(240, 240, 240)"}]
   [:.bg-color {:background-color "rgb(230, 230, 230)"}]
   ["*" {:font-family "monospace !important"}]
   [:.MuiIconButton-root {:border-radius "10%"}]
   [:.MuiAppBar-colorPrimary {:background-color "rgb(230, 230, 230)"}]
   [".menu-button .MuiSvgIcon-root" {:width "40px"
                                     :height "40px"}]))

(def card-style
  {:style {:padding "20px"
           :margin-bottom "10px"}
   :class "bg-color"})

(def max-retries 7)

(defn api-delete [route json-params]
  (go-loop [i 0]
    (let [resp (<! (http/delete route
                                {:json-params json-params
                                 :with-credentials? false}))]
      (cond
        (= 200 (:status resp)) resp
        (< i max-retries) (do (<! (a/timeout (* i 100)))
                              (recur (inc i)))
        :else (throw "failed after several tries")))))

(defn api-post [route json-params]
  (go-loop [i 0]
    (let [resp (<! (http/post route
                              {:json-params json-params
                               :with-credentials? false}))]
      (cond
        (= 200 (:status resp)) resp
        (< i max-retries) (do (<! (a/timeout (* i 100)))
                              (recur (inc i)))
        :else (throw "failed after several tries")))))

(defn api-get [route query-params]
  (go-loop [i 0]
    (let [resp (<! (http/get route
                             {:query-params query-params
                              :with-credentials? false}))]
      (cond
        (= 200 (:status resp)) resp
        (< i max-retries) (do (<! (a/timeout (* i 100)))
                              (recur (inc i)))
        :else (throw "failed after several tries")))))

(defn component-home []
  [:> mui/Card card-style
   [:> mui/Typography
    "home"]])

(defn component-files []
  [:> mui/Card card-style
   [:> mui/Typography
    "files"]])

(defn component-search []
  [:<>
   (for [line (remove s/blank? (s/split (:search-text @state) #"/"))]
     ^{:key (gen-id)} [:> mui/Card card-style
                       [:> mui/Typography line]])])

(goog-define ws-domain "") ;; defined via environment variable PROJECT_DOMAIN_WEBSOCKET

(defn start-websocket []
  (go
    (let [uid (js/Date.now)
          url (str "wss://" ws-domain)
          new-stream #(go (let [stream (<! (ws/connect url {}))]
                            (swap! state assoc :websocket-stream stream)
                            (js/console.log "stream" stream)
                            stream))]
      (loop [stream (<! (new-stream))]
        (a/alt!
          (:in stream) ([data] (let [val (js->clj (js/JSON.parse data) :keywordize-keys true)]
                                 (swap! state assoc :websocket-time (:time val))
                                 (when (= stream (:websocket-stream @state))
                                   (recur stream))))
          (:close-status stream) ([close-status] (recur (<! (new-stream)))))))))

(defn component-websocket []
  [:> mui/Card card-style
   [:> mui/Typography "time: " (:websocket-time @state)]])

(defn component-api []
  (if (nil? (:time @state))
    [:> mui/Card card-style
     [:> mui/LinearProgress {:style {:height "13px" :margin "2px"}}]]
    [:> mui/Card card-style
     [:> mui/Typography (str "time: " (:time @state))]]))

(defn component-not-found []
  [:div
   [:p "404"]])

(defn component-menu-button [page-name page-component icon]
  [:> mui/IconButton
   {:id page-name
    :disable-ripple true
    :class "menu-button"
    :href (str "#/" page-name)
    :style  (merge {:padding "15px"}
                   (if (= page-component (:page @state))
                     {:color "red"}))}
   [:> mui/Grid
    [icon]
    [:> mui/Typography
     {:style {:font-weight 700}}
     page-name]]])


(defn component-help []
  [:> mui/Grid {:spacing 0
                :alignItems "center"
                :justify "center"
                :style {:display "flex"
                        :flexDirection "column"
                        :justifyContent "center"
                        :minHeight "100vh"}}
   [:> mui/Card {:style {:padding "20px"}}
    [:strong "keyboard shortcuts"]
    [:ul {:style {:padding-left "25px"}}
     [:li "/ : search"]]]])

(def search-ref (atom nil))

;; For some reason the new MUI doesn't pass ref in the props,
;; but we can get it using forwardRef?
;; This is someone incovenient as we need to convert props to Cljs
;; but reactify-component would also do that.
(def ^:private input-component
  (react/forwardRef
   (fn [props ref]
     (reagent/as-element
      [:input (-> (js->clj props :keywordize-keys true)
                (assoc :ref ref))]))))

(def ^:private textarea-component
  (react/forwardRef
   (fn [props ref]
     (reagent/as-element
      [:textarea (-> (js->clj props :keywordize-keys true)
                   (assoc :ref ref))]))))

;; To fix cursor jumping when controlled input value is changed,
;; use wrapper input element created by Reagent instead of
;; letting Material-UI to create input element directly using React.
;; Create-element + convert-props-value is the same as what adapt-react-class does.
(defn text-field [props & children]
  (let [props (-> props
                (assoc-in [:InputProps :inputComponent]
                          (cond
                            (and (:multiline props) (:rows props) (not (:maxRows props)))
                            textarea-component

                            ;; FIXME: Autosize multiline field is broken.
                            (:multiline props)
                            nil

                            ;; Select doesn't require cursor fix so default can be used.
                            (:select props)
                            nil

                            :else
                            input-component))
                ;; FIXME: Internal fn should not be used
                ;; clj->js is not enough as prop on-change -> onChange, class -> classNames etc should be handled
                rtpl/convert-prop-value)]
    (apply reagent/create-element mui/TextField props (map reagent/as-element children))))

(defn component-main []
  [:<>
   [:> mui/AppBar
    {:position "relative"}
    [:> mui/Toolbar
     {:style {:padding 0}}
     [component-menu-button "home" component-home (adapt octo/HomeIcon)]
     [component-menu-button "files" component-files  (adapt octo/FileDirectoryIcon)]
     [component-menu-button "api" component-api (adapt octo/GlobeIcon)]
     [component-menu-button "websocket" component-home (adapt octo/ServerIcon)]
     [text-field
      {:label "search"
       :ref #(reset! search-ref %)
       :id "search"
       :autoComplete "off"
       :spellCheck false
       :multiline false
       :fullWidth true
       :focused (:search-focus @state)
       :value (:search-text @state)
       :on-focus #(swap! state assoc :search-focus true)
       :on-blur #(swap! state assoc :search-focus false)
       :on-change #(swap! state assoc :search-text (target-value %))
       :style {:margin-right "20px"
               :margin-left "5px"
               :min-width "150px"}}]]]
   [:> mui/Container {:id "content" :style {:padding 0 :margin-top "10px"}}
    [(:page @state)]]])

(defn component-root []
  [:<>
   [:style style]
   (if (:modal-open @state)
     [component-help]
     [component-main])])

(defn mousedown-listener [e]
  nil)

(defn navigate-to [page]
  (let [a (js/document.createElement "a")]
    (.setAttribute a "href", (str "/#" page))
    (.click a)
    (.remove a)))

(defn keydown-listener [e]
  (cond
    (:modal-open @state) (swap! state assoc :modal-open false)
    (= "Enter" (event-key e)) (when (:search-focus @state)
                                (swap! state update-in [:search-text] str "/Enter"))
    (= "?" (event-key e))    (swap! state assoc :modal-open true)
    (#{"INPUT" "TEXTAREA"} (.-tagName js/document.activeElement)) nil
    (= "/" (event-key e)) (when-not (:search-focus @state)
                            (focus (query-selector @search-ref "input"))
                            (swap! state merge {:search-focus true})
                            (prevent-default e))
    :else nil))

(defn url []
  (last (s/split js/window.location.href #"#/")))

(defn href-parts []
  (s/split (url) #"/"))

(defn defwatch [key f]
  (add-watch state key (fn [key atom old new]
                         (when (not= (get old key)
                                     (get new key))
                           (f (get new key))))))

(defwatch :search-text
  (fn [text]
    (navigate-to (str "/search/" ))))

(defwatch :search-focus
  (fn [focus]
    (when (and focus (not (s/starts-with? (url) "/search/")))
      (navigate-to "/search/"))))

(defn on-navigate [component data]
  (when (= component-api component)
    (swap! state assoc :time nil)
    (go (let [resp (<! (api-get "/api/time" {}))]
          (swap! state assoc :time (:time (:body resp))))))
  (swap! state merge {:page component :parts (href-parts)}))

(defn document-listener [name f]
  (let [key (keyword (str name "-listener"))]
    (when-not (key @state)
      (.addEventListener js/document name f)
      (swap! state assoc key true))))

(def router
  [["/" component-home]
   ["/home" component-home]
   ["/files" component-files]
   ["/search" component-search]
   ["/search/(.*)" component-search]
   ["/websocket" component-websocket]
   ["/api" component-api]
   ["(.*)" component-not-found]])

(defn start-router []
  (bide/start! (bide/router router) {:default "/"
                                     :on-navigate on-navigate
                                     :html5? false}))

(defn reagent-render []
  (reagent.dom/render [component-root] (js/document.getElementById "app")))

(defn ^:dev/after-load main []
  (start-websocket)
  (start-router)
  (document-listener "keydown" keydown-listener)
  (document-listener "mousedown" mousedown-listener)
  (reagent-render))
