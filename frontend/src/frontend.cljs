(ns frontend
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs-http.client :as http]
            [cljs.core.async :refer [<! >! chan timeout] :as a]
            [cljs.pprint :as pp]
            [reagent.dom :as reagent.dom]
            [reagent.core :as reagent]
            [reagent.impl.template :as rtpl]
            [bide.core :as bide]
            [garden.core :as garden]
            [garden.stylesheet :as gs]
            [clojure.string :as s]
            [haslett.client :as ws]
            ["react-syntax-highlighter/dist/esm/languages/prism/go$default" :as go-prism]
            ["react-syntax-highlighter/dist/esm/languages/prism/markdown$default" :as markdown-prism]
            ["react-syntax-highlighter/dist/esm/prism-light.js$default" :as SyntaxHighlighter]
            ["react-syntax-highlighter/dist/esm/styles/prism/darcula$default" :as prism-theme]
            ["react" :as react]
            ["@mui/material" :as mui]
            ["@primer/octicons-react" :as octo]))

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

(defonce state
  (reagent/atom
   {:modal-open false
    :search-focus false
    :key-listener false
    :mouse-listener false
    :search-text ""
    :page nil
    :time nil
    :drawer-open false}))

(def solarized-base03  "#002b36")
(def solarized-base02  "#073642")
(def solarized-base01  "#586e75")
(def solarized-base00  "#657b83")
(def solarized-base0   "#839496")
(def solarized-base1   "#93a1a1")
(def solarized-base2   "#eee8d5")
(def solarized-base3   "#fdf6e3")

(def solarized-yellow  "#b58900")
(def solarized-orange  "#cb4b16")
(def solarized-red     "#dc322f")
(def solarized-magenta "#d33682")
(def solarized-violet  "#6c71c4")
(def solarized-blue    "#268bd2")
(def solarized-cyan    "#2aa198")
(def solarized-green   "#859900")

(def style
  (garden/css
   [:body {:background-color "rgb(240, 240, 240)"
           :overflow-x "hidden"}]

   [:.bg-color {:background-color "rgb(230, 230, 230)"}]
   ["*" {:font-family "monospace !important"}]
   [:.MuiIconButton-root {:border-radius "10%"}]
   [:.MuiAppBar-colorPrimary {:background-color "rgb(230, 230, 230)"}]
   [".menu-button .MuiSvgIcon-root" {:width "40px"
                                     :height "40px"}]
   [".menu-button:hover" {:color solarized-blue}]
   (gs/at-media {:max-width "600px"}
                [:.mobile-hide {:display "none !important"}]
                [:.desktop-hide {:display "block !important"}])
   (gs/at-media {:min-width "601px"}
                [:.mobile-hide {:display "block !important"}]
                [:.desktop-hide {:display "none !important"}])))

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
    "just ship fullstack web on aws!"]])

(.registerLanguage SyntaxHighlighter "go" go-prism)
(.registerLanguage SyntaxHighlighter "markdown" markdown-prism)

(defn lang [file]
  (condp = (last (s/split (last (s/split (:name file) #"/")) #"\."))
    "md" "markdown"
    "go" "go"
    nil))

(defn component-file-content [file]
  (condp = (last (s/split (last (s/split (:name file) #"/")) #"\."))
    "gif"  [:img {:src (str "data:image/png;base64," (:content file))
                  :style {:width "100%"}}]
    "png"  [:img {:src (str "data:image/png;base64," (:content file))
                  :style {:width "100%"}}]
    "jpg"  [:img {:src (str "data:image/png;base64," (:content file))
                  :style {:width "100%"}}]
    "jpeg" [:img {:src (str "data:image/png;base64," (:content file))
                  :style {:width "100%"}}]
    [(adapt SyntaxHighlighter)
     {:language        (lang file)
      :style           prism-theme
      :custom-style {:background nil}
      :show-line-numbers true}
     (s/replace (:content file) #"\t" "    ")]))

(def files-rows [{:id 1
                  :name "cmd/"
                  :size 0
                  :modified "2025-04-12"}
                 {:id 2
                  :name "server.go"
                  :size 224
                  :modified "2025-04-11"}
                 {:id 3
                  :name "go.mod"
                  :size 45
                  :modified "2025-04-10"}
                 {:id 4
                  :name "README.md"
                  :size 128
                  :modified "2025-04-09"}])

(def files-subdir-rows {"cmd" [{:id 10
                                :name "main.go"
                                :size 300
                                :modified "2025-04-12"}
                               {:id 20
                                :name "cli.go"
                                :size 140
                                :modified "2025-04-11"}]})

(def files-contents {"server.go" ["package main"
                                  ""
                                  "import ("
                                  "    \"net/http\""
                                  "    \"fmt\""
                                  ")"
                                  ""
                                  "func main() {"
                                  "    http.HandleFunc(\"/\", func(w http.ResponseWriter, r *http.Request) {"
                                  "        fmt.Fprintln(w, \"Hello, world!\")"
                                  "    })"
                                  "    http.ListenAndServe(\":8080\", nil)"
                                  "}"]
                     "main.go"   ["package main"
                                  ""
                                  "import \"fmt\""
                                  ""
                                  "func main() {"
                                  "    fmt.Println(\"CLI entrypoint\")"
                                  "}"]
                     "cli.go"    ["package main"
                                  ""
                                  "func doSomething() {}"]
                     "go.mod"    ["module example.com/myapp"
                                  ""
                                  "go 1.22"]
                     "README.md" ["# MyApp"
                                  ""
                                  "Some documentation..."]})

(defn component-files []
  (let [parts (:parts @state)
        subpath (when (> (count parts) 1)
                  (s/join "/" (drop 1 parts)))]
    (if subpath
      (if (= subpath "cmd")
        (let [sub-files (get files-subdir-rows subpath [])]
          [:> mui/Card card-style
           [:> mui/Box
            {:style {:border "1px solid #d0d7de"
                     :borderRadius "6px"
                     :marginTop "0"
                     :overflow "hidden"}}
            [:div
             {:style {:padding "8px"
                      :borderBottom "1px solid #d0d7de"
                      :display "flex"}}
             [:div
              [:a
               {:href "#/files"
                :style {:marginLeft "6px"
                        :color "rgb(50,50,50)"
                        :textDecoration "none"
                        :display "inline-flex"
                        :alignItems "center"
                        :cursor "pointer"}}
               "repo"]
              (when subpath
                (let [crumb-parts (s/split subpath #"/")
                      cnt (count crumb-parts)]
                  [:<>
                   (doall
                    (map-indexed
                     (fn [idx c]
                       (if (< idx (dec cnt))
                         [:<>
                          " / "
                          [:a
                           {:href (str "#/files/" (s/join "/" (take (inc idx) crumb-parts)))
                            :style {:marginLeft "3px"
                                    :color "rgb(50,50,50)"
                                    :textDecoration "none"
                                    :display "inline-flex"
                                    :alignItems "center"
                                    :cursor "pointer"}}
                           c]]
                         [:<>
                          " / "
                          [:span
                           {:style {:marginLeft "3px"
                                    :display "inline-flex"
                                    :alignItems "center"}}
                           c]]))
                     crumb-parts))]))]]
            (for [row sub-files]
              ^{:key (:id row)}
              [:div
               {:style {:padding "8px"
                        :borderBottom "1px solid #d0d7de"
                        :display "flex"
                        :&:hover {:backgroundColor "#f6f8fa"}}}
               [:div
                {:style {:flex "1"
                         :display "flex"
                         :alignItems "center"}}
                (if (s/ends-with? (:name row) "/")
                  [:> octo/FileDirectoryFillIcon {:style {:color "#57606a"}}]
                  [:> octo/FileIcon {:style {:color "#57606a"}}])
                [:a
                 {:href (str "#/files/" subpath "/" (:name row))
                  :style {:marginLeft "6px"
                          :color "rgb(50,50,50)"
                          :textDecoration "none"
                          :display "inline-flex"
                          :alignItems "center"
                          :cursor "pointer"}}
                 [:> mui/Typography (:name row)]]]
               [:div
                {:style {:flex "1"
                         :display "flex"
                         :justifyContent "center"
                         :alignItems "center"}}
                [:> mui/Typography (str (:size row) " bytes")]]
               [:div
                {:style {:flex "1"
                         :display "flex"
                         :justifyContent "flex-end"
                         :alignItems "center"}}
                [:> mui/Typography (:modified row)]]])]])
        (let [raw-contents (get files-contents (last (s/split subpath #"/"))
                                ["No content found."])
              file {:name subpath
                    :content (if (coll? raw-contents)
                               (s/join "\n" raw-contents)
                               raw-contents)}]
          [:> mui/Card card-style
           [:> mui/Box
            {:style {:border "1px solid #d0d7de"
                     :borderRadius "6px"
                     :marginTop "0"
                     :overflow "hidden"
                     :overflowX "auto"}}
            [:div
             {:style {:padding "8px"
                      :borderBottom "1px solid #d0d7de"
                      :display "flex"}}
             [:div
              [:a
               {:href "#/files"
                :style {:marginLeft "6px"
                        :color "rgb(50,50,50)"
                        :textDecoration "none"
                        :display "inline-flex"
                        :alignItems "center"
                        :cursor "pointer"}}
               "repo"]
              (when subpath
                (let [crumb-parts (s/split subpath #"/")
                      cnt (count crumb-parts)]
                  [:<>
                   (doall
                    (map-indexed
                     (fn [idx c]
                       (if (< idx (dec cnt))
                         ^{:key idx}
                         [:<>
                          " / "
                          [:a
                           {:href (str "#/files/" (s/join "/" (take (inc idx) crumb-parts)))
                            :style {:marginLeft "3px"
                                    :color "rgb(50,50,50)"
                                    :textDecoration "none"
                                    :display "inline-flex"
                                    :alignItems "center"
                                    :cursor "pointer"}}
                           c]]
                         ^{:key idx}
                         [:<>
                          " / "
                          [:span
                           {:style {:marginLeft "3px"
                                    :display "inline-flex"
                                    :alignItems "center"}}
                           c]]))
                     crumb-parts))]))]]
            [component-file-content file]]]))
      [:> mui/Card card-style
       [:> mui/Box
        {:style {:border "1px solid #d0d7de"
                 :borderRadius "6px"
                 :marginTop "0"
                 :overflow "hidden"}}
        [:div
         {:style {:padding "8px"
                  :borderBottom "1px solid #d0d7de"
                  :display "flex"}}
         [:div
          [:a
           {:href "#/files"
            :style {:marginLeft "6px"
                    :textDecoration "none"
                    :color "rgb(50,50,50)"
                    :display "inline-flex"
                    :alignItems "center"
                    :cursor "pointer"}}
           "repo"]]]
        (for [row files-rows]
          ^{:key (:id row)}
          [:div
           {:style {:padding "8px"
                    :borderBottom "1px solid #d0d7de"
                    :display "flex"
                    :&:hover {:backgroundColor "#f6f8fa"}}}
           [:div
            {:style {:flex "1"
                     :display "flex"
                     :alignItems "center"}}
            (if (s/ends-with? (:name row) "/")
              [:> octo/FileDirectoryFillIcon {:style {:color "#57606a"}}]
              [:> octo/FileIcon {:style {:color "#57606a"}}])
            [:a
             {:href (str "#/files/" (:name row))
              :style {:marginLeft "6px"
                      :color "rgb(50,50,50)"
                      :textDecoration "none"
                      :display "inline-flex"
                      :alignItems "center"
                      :cursor "pointer"}}
             [:> mui/Typography (:name row)]]]
           [:div
            {:style {:flex "1"
                     :display "flex"
                     :justifyContent "center"
                     :alignItems "center"}}
            [:> mui/Typography (str (:size row) " bytes")]]
           [:div
            {:style {:flex "1"
                     :display "flex"
                     :justifyContent "flex-end"
                     :alignItems "center"}}
            [:> mui/Typography (:modified row)]]])]])))

(defn component-search []
  [:<>
   (for [line (remove s/blank? (s/split (:search-text @state) #"/"))]
     ^{:key line}
     [:> mui/Card card-style
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
  [:<>
   [:> mui/Card card-style
    [:> mui/Button {:full-width true
                    :variant :contained
                    :on-click #(do (swap! state merge {:api-progress true
                                                       :api-resp nil})
                                   (go (let [resp (<! (api-get "/api/time" {}))]
                                         (swap! state merge {:api-progress nil
                                                             :api-resp resp}))))
                    :style {:background-color :grey}}
     "fetch time"]]
   (when (:api-progress @state)
     [:> mui/Card card-style
      [:> mui/LinearProgress
       {:style {:width "100%"
                :height "20px"}}]])
   (when-let [resp (:api-resp @state)]
     [:> mui/Card card-style
      [:> mui/Typography
       (str "status: " (:status resp))]
      [:> mui/Typography {:style {:margin-top "10px"}}
       (str "time: " (:time (:body resp)))]])])

(defn component-not-found []
  [:div
   [:p "404"]])

(defn component-menu-button
  [page-name page-component icon & {:keys [drawer?]
                                    :or   {drawer? false}}]
  (let [drawer-open? (:drawer-open @state)]
    [:> mui/IconButton
     {:id page-name
      :disable-ripple true
      :class "menu-button"
      :href (str "#/" page-name)
      :on-click #(when drawer-open?
                   (swap! state assoc :drawer-open false))
      :style  (merge {:padding "15px"
                      :padding-bottom "5px"}
                     (if (= page-component (:page @state))
                       {:color solarized-red}))}
     (if drawer?
       [:> mui/Grid
        {:style {:display "flex"
                 :alignItems "center"
                 :justifyContent "flex-start"}}
        [icon]
        [:> mui/Typography
         {:style {:font-weight 700
                  :margin-left "6px"
                  :margin-top "0px"
                  :margin-bottom "0px"}}
         page-name]]
       [:> mui/Grid
        {:style {:display "flex"
                 :flex-direction "column"
                 :alignItems "center"}}
        [icon]
        [:> mui/Typography
         {:style {:font-weight 700
                  :margin-top "3px"
                  :margin-bottom "5px"}}
         page-name]])]))

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
    [:ul {:style {:padding-left "25px"
                  :list-style-type :none}}
     [:li "/ search"]]]])

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

(defn component-form []
  [:<>
   [:> mui/Card card-style
    [text-field
     {:label "enter your data"
      :id "data"
      :variant :outlined
      :full-width true
      :focused (:data-focus @state)
      :value (:data-text @state)
      :on-focus #(swap! state assoc :data-focus true)
      :on-blur #(swap! state assoc :data-focus false)
      :on-change #(swap! state assoc :data-text (target-value %))
      :style {:margin-bottom "20px"
              :min-width "150px"}}]
    [text-field
     {:label "enter more of your data"
      :id "more-data"
      :full-width true
      :variant :outlined
      :focused (:more-data-focus @state)
      :value (:more-data-text @state)
      :on-focus #(swap! state assoc :more-data-focus true)
      :on-blur #(swap! state assoc :more-data-focus false)
      :on-change #(swap! state assoc :more-data-text (target-value %))
      :style {:margin-bottom "20px"
              :min-width "150px"}}]
    [:> mui/Button {:full-width true
                    :variant :contained
                    :on-click #(do (swap! state merge {:form-progress true
                                                       :form-resp nil})
                                   (go (let [req {:data (:data-text @state)
                                                  :more-data (:more-data-text @state)}
                                             resp (<! (api-post "/api/data" req))]
                                         (swap! state merge {:form-progress nil
                                                             :form-resp resp}))))
                    :style {:background-color :grey}}
     "submit"]]
   (when (:form-progress @state)
     [:> mui/Card card-style
      [:> mui/LinearProgress
       {:style {:width "100%"
                :height "20px"}}]])
   (when-let [resp (:form-resp @state)]
     [:> mui/Card card-style
      [:> mui/Typography
       (str "status: " (:status resp))]
      [:> mui/Typography {:style {:margin-top "10px"}}
       (str "message: " (:message (:body resp)))]])])

(defn component-drawer []
  [:> mui/Drawer
   {:anchor "left"
    :open (:drawer-open @state)
    :onClose #(swap! state assoc :drawer-open false)}
   [:> mui/Box
    {:style {:width "100%" :height "100%"}
     :onClick (fn [e]
                (when (identical? (.-target e) (.-currentTarget e))
                  (swap! state assoc :drawer-open false)))}
    [:> mui/List
     {:disablePadding true
      :style {:padding 0}}
     [:> mui/ListItem {:style {:margin-top "20px"}}
      [component-menu-button "home" component-home (adapt octo/HomeIcon) :drawer? true]]
     [:> mui/ListItem
      [component-menu-button "files" component-files (adapt octo/FileDirectoryIcon) :drawer? true]]
     [:> mui/ListItem
      [component-menu-button "api" component-api (adapt octo/GlobeIcon) :drawer? true]]
     [:> mui/ListItem
      [component-menu-button "form" component-form (adapt octo/DatabaseIcon) :drawer? true]]
     [:> mui/ListItem
      [component-menu-button "websocket" component-websocket (adapt octo/ServerIcon) :drawer? true]]]]])

(defn component-hamburger-button []
  [:> mui/IconButton
   {:disable-ripple true
    :class "menu-button desktop-hide"
    :style {:padding "17px"
            :padding-bottom "13px"}
    :on-click #(swap! state update :drawer-open not)}
   [:> mui/Grid
    {:style {:display "flex" :flex-direction "column" :alignItems "center"}}
    [(adapt octo/ThreeBarsIcon)]
    [:> mui/Typography
     {:style {:font-weight 700
              :margin-top "0px"
              :margin-bottom "0px"
              }}
     "menu"]]])

(defn component-main []
  [:<>
   [:> mui/AppBar
    {:position "relative"}
    [:> mui/Toolbar
     {:style {:padding 0
              :white-space "nowrap"
              :overflow "hidden"}}
     [:div {:class "mobile-hide"
            :style {:display "flex"
                    :flex-wrap "nowrap"}}
      [component-menu-button "home" component-home (adapt octo/HomeIcon)]
      [component-menu-button "files" component-files  (adapt octo/FileDirectoryIcon)]
      [component-menu-button "api" component-api (adapt octo/GlobeIcon)]
      [component-menu-button "form" component-form (adapt octo/DatabaseIcon)]
      [component-menu-button "websocket" component-websocket (adapt octo/ServerIcon)]]
     [component-hamburger-button]
     [text-field
      {:label "search"
       :ref #(reset! search-ref %)
       :variant :outlined
       :id "search"
       :full-width true
       :focused (:search-focus @state)
       :value (:search-text @state)
       :on-focus #(swap! state assoc :search-focus true)
       :on-blur #(swap! state assoc :search-focus false)
       :on-change #(swap! state assoc :search-text (target-value %))
       :style {:margin-right "20px"
               :margin-left "5px"
               :min-width "150px"}}]]]
   [component-drawer]
   [:> mui/Container {:id "content" :max-width false :style {:padding 0 :margin-top "10px"}}
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
    (.setAttribute a "href" (str "/#" page))
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
    (navigate-to (str "/search/"))))

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
   ["/files/(.*)" component-files]
   ["/search" component-search]
   ["/search/(.*)" component-search]
   ["/websocket" component-websocket]
   ["/api" component-api]
   ["/form" component-form]
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
