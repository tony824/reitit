(ns reitit.frontend.history
  ""
  (:require [reitit.core :as reitit]
            [goog.events :as e]
            [reitit.core :as r]
            [reitit.frontend :as rf]
            [reitit.impl :as impl])
  (:import goog.history.Html5History
           goog.Uri))

;; Token is for Closure HtmlHistory
;; Path is for reitit

(defn- token->path [history token]
  (if (.-useFragment_ history)
    ;; If no fragment at all, default to "/"
    ;; If fragment is present, the token already is prefixed with "/"
    (if (= "" token)
      (.getPathPrefix history)
      token)
    (str (.getPathPrefix history) token)))

(defn- path->token [history path]
  (subs path (if (.-useFragment_ history)
               1
               (count (.getPathPrefix history)))))

(defn- token->href [history token]
  (if token
    (str (if (.-useFragment_ history)
           (str "#"))
         (.getPathPrefix history)
         token)))

(def ^:private current-domain (if (exists? js/location)
                                (.getDomain (.parse Uri js/location))))

(defn ignore-anchor-click
  "Ignore click events from a elements, if the href points to URL that is part
  of the routing tree."
  [router history e]
  ;; Returns the next matching anchestor of event target
  (when-let [el (.closest (.-target e) "a")]
    (let [uri (.parse Uri (.-href el))]
      (when (and (or (and (not (.hasScheme uri)) (not (.hasDomain uri)))
                     (= current-domain (.getDomain uri)))
                 (not (.-altKey e))
                 (not (.-ctrlKey e))
                 (not (.-metaKey e))
                 (not (.-shiftKey e))
                 (not (contains? #{"_blank" "self"} (.getAttribute el "target")))
                 ;; Left button
                 (= 0 (.-button e))
                 (reitit/match-by-path router (.getPath uri)))
          (.preventDefault e)
          (.setToken history (path->token history (str (.getPath uri)
                                                       (if (seq (.getQuery uri))
                                                         (str "?" (.getQuery uri))))))))))

(impl/goog-extend
  ^{:jsdoc ["@constructor"
            "@extends {Html5History.TokenTransformer}"]}
  TokenTransformer
  Html5History.TokenTransformer
  ([]
   (this-as this
     (.call Html5History.TokenTransformer this)))
  (retrieveToken [path-prefix location]
    (subs (.-pathname location) (count path-prefix)))
  (createUrl [token path-prefix location]
    ;; Code in Closure also adds current query params
    ;; from location.
    (str path-prefix token)))

(defn start!
  "This registers event listeners on either haschange or HTML5 history.
  When using with development workflow like Figwheel, rememeber to
  remove listeners using stop! call before calling start! again.

  Parameters:
  - router         The reitit routing tree.
  - on-navigate    Function to be called when route changes.

  Options:
  - :use-fragment  (default true) If true, onhashchange and location hash are used to store the token.
  - :path-prefix   (default \"/\") If :use-fragment is false, this is prepended to all tokens, and is
  removed from start of the token before matching the route."
  [router
   on-navigate
   {:keys [path-prefix use-fragment]
    :or {path-prefix "/"
         use-fragment true}}]
  (let [history
        (doto (Html5History. nil (TokenTransformer.))
          (.setEnabled true)
          (.setPathPrefix path-prefix)
          (.setUseFragment use-fragment))

        event-key
        (e/listen history goog.history.EventType.NAVIGATE
                  (fn [e]
                    (on-navigate (rf/match-by-path router (token->path history (.getToken history))))))

        click-listen-key
        (if-not use-fragment
          (e/listen js/document e/EventType.CLICK
                    (partial ignore-anchor-click router history)))]

    ;; Trigger navigate event for current route
    (on-navigate (rf/match-by-path router (token->path history (.getToken history))))

    {:router router
     :history history
     :close-fn (fn []
                 (e/unlistenByKey event-key)
                 (e/unlistenByKey click-listen-key)
                 (.dispose history))}))

(defn stop! [{:keys [close-fn]}]
  (if close-fn
    (close-fn)))

(defn- match->token [history match k params query]
  (some->> (r/match->path match query)
           (path->token history)))

(defn href
  ([state k]
   (href state k nil))
  ([state k params]
   (href state k params nil))
  ([{:keys [router history]} k params query]
   (let [match (rf/match-by-name! router k params)
         token (match->token history match k params query)]
     (token->href history token))))

(defn set-token
  "Sets the new route, leaving previous route in history."
  ([state k]
   (set-token state k nil))
  ([state k params]
   (set-token state k params nil))
  ([{:keys [router history]} k params query]
   (let [match (rf/match-by-name! router k params)
         token (match->token history match k params query)]
     (.setToken history token))))

(defn replace-token
  "Replaces current route. I.e. current route is not left on history."
  ([state k]
   (replace-token state k nil))
  ([state k params]
   (replace-token state k params nil))
  ([{:keys [router history]} k params query]
   (let [match (rf/match-by-name! router k params)
         token (match->token history match k params query)]
     (.replaceToken history token))))
