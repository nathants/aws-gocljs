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
            [reagent-mui.material.app-bar :refer [app-bar]]
            [reagent-mui.material.card :refer [card]]
            [reagent-mui.material.text-field :refer [text-field]]
            [reagent-mui.material.container :refer [container]]
            [reagent-mui.material.grid :refer [grid]]
            [reagent-mui.material.linear-progress :refer [linear-progress]]
            [reagent-mui.material.icon-button :refer [icon-button]]
            [reagent-mui.material.toolbar :refer [toolbar]]
            [reagent-mui.material.typography :refer [typography]]
            [reagent-mui.icons.home :refer [home]]
            [reagent-mui.icons.sms :refer [sms]]
            [reagent-mui.icons.access-time :refer [access-time]]
            [reagent-mui.icons.folder :refer [folder]]))

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
  [card card-style
   [typography "home"]])

(defn component-files []
  [card card-style
   [typography "files"]])

(defn component-search []
  [:<>
   (for [line (remove s/blank? (s/split (:search-text @state) #"/"))]
     ^{:key (gen-id)} [card card-style
                       [typography line]])])

(goog-define ws-domain "") ;; defined via environment variable PROJECT_DOMAIN_WEBSOCKET

(defn start-websocket []
  (go
    (let [uid (js/Date.now)
          url (str "wss://" ws-domain)
          new-stream #(go (let [stream (<! (ws/connect url {}))]
                            (swap! state assoc :websocket-stream stream)
                            stream))]
      (loop [stream (<! (new-stream))]
        (a/alt!
          (:source stream) ([data] (let [val (js->clj (js/JSON.parse data) :keywordize-keys true)]
                                     (swap! state assoc :websocket-time (:time val))
                                     (when (= stream (:websocket-stream @state))
                                       (recur stream))))
          (:close-status stream) ([close-status] (recur (<! (new-stream)))))))))

(defn component-websocket []
  [card card-style
   [typography "time: " (:websocket-time @state)]])

(defn component-api []
  (if (nil? (:time @state))
    [card card-style
     [linear-progress {:style {:height "13px" :margin "2px"}}]]
    [card card-style
     [typography (str "time: " (:time @state))]]))

(defn component-not-found []
  [:div
   [:p "404"]])

(defn component-menu-button [page-name page-component icon]
  [icon-button {:id page-name
                :disable-ripple true
                :class "menu-button"
                :href (str "#/" page-name)
                :style  (merge {:padding "15px"}
                               (if (= page-component (:page @state))
                                 {:color "red"}))}
   [grid
    [icon]
    [typography {:style {:font-weight 700}} page-name]]])

(defn component-help []
  [grid {:spacing 0
         :alignItems "center"
         :justify "center"
         :style {:display "flex"
                 :flexDirection "column"
                 :justifyContent "center"
                 :minHeight "100vh"}}
   [card {:style {:padding "20px"}}
    [:strong "keyboard shortcuts"]
    [:ul {:style {:padding-left "25px"}}
     [:li "h : home"]
     [:li "f : files"]
     [:li "d : dms"]
     [:li "/ : search"]]]])

(def search-ref (atom nil))

(defn component-main []
  [:<>
   [app-bar {:position "relative"}
    [toolbar {:style {:padding 0}}
     [component-menu-button "home" component-home  home]
     [component-menu-button "files" component-files  folder]
     [component-menu-button "api" component-api access-time]
     [component-menu-button "websocket" component-websocket sms]
     [text-field {:placeholder "search"
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
                  :style {:margin-left "0px" :padding-right "20px" :padding-left "5px"}}]]]
   [container {:id "content" :style {:padding 0 :margin-top "10px"}}
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
    (= "h" (event-key e)) (navigate-to "/home")
    (= "f" (event-key e)) (navigate-to "/files")
    (= "d" (event-key e)) (navigate-to "/dms")
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
                           (f  (get new key))))))

(defwatch :search-text
  (fn [text]
    (navigate-to (str "/search/" text))))

(defwatch :search-focus
  (fn [focus]
    (when (and focus (not (s/starts-with? (url) "/search/")))
      (navigate-to (str "/search/" (:search-text @state))))))

(defn on-navigate [component data]
  (when (= component-search component)
    (when-let [val (:0 data)]
      (swap! state assoc :search-text (js/decodeURIComponent val))))
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
