(ns pedestal.content-negotiation
  "Content negotiation for Pedestal web services."
  (:require [clojure.string :refer [join split trim]]
            [clojure.data.json :as json]
            [io.pedestal.service.interceptor :refer [around]]
            [io.pedestal.service.impl.interceptor :refer [terminate]])
  (:import [java.io OutputStreamWriter]
           [java.util.zip GZIPOutputStream]))

(def ^:private header-keys ["accept" "accept-charset" "accept-encoding"])
(def ^:private content-type-wildcard "*/*")
(def ^:private charset-wildcard "*")
(def ^:private encoding-wildcard "*")
(def ^:private default-content-types ["application/edn" "application/json"])
(def ^:private default-charsets ["utf-8"])
(def ^:private default-encodings ["identity" "gzip"])

(defn route
  "Returns a content negotiation route from a possibly incomplete
  route.  A route contains the keys :content-type,
  :content-type-params, :charset, and :encoding.  These correspond to
  the \"accept\", \"accept-charset\", and \"accept-encoding\" headers
  in an HTTP request.  A route specifies exactly one valid setting, so
  it is common for an HTTP request's headers to specify many routes."
  {:arglists '([m])}
  [{:keys [content-type content-type-params charset encoding]
    :or {content-type content-type-wildcard
         content-type-params {}
         charset charset-wildcard
         encoding encoding-wildcard}}]
  {:content-type content-type
   :content-type-params content-type-params
   :charset charset
   :encoding encoding})

(def default-wildcard-map
  "The default wildcard map for use with replace-wildcards."
  {:content-type {"*/*" "application/edn"
                  "application/*" "application/edn"}
   :charset "utf-8"
   :encoding "identity"})

(defn replace-wildcards
  "Replaces any wildcard values in route.  A wildcard map contains
  values for :charset and :encoding that are used to replace their
  corresponding values in route if they are set to \"*\".  It also
  contains a map for :content-type that pairs a content-type wildcard
  like \"application/*\" with a replacement value.  The default
  wildcard map is default-wildcard-map."
  ([route]
     (replace-wildcards default-wildcard-map route))
  ([wildcard-map route]
     (letfn [(replace-wildcard [m k]
               (update-in m [k]
                          (fn [s] (if (= s "*") (get wildcard-map k) s))))]
       (-> route
           (update-in [:content-type]
                      (fn [s] (get-in wildcard-map [:content-type s] s)))
           (replace-wildcard :charset)
           (replace-wildcard :encoding)))))

(defn- content-type->str
  "Returns a string formatted for the content-type or accept headers."
  [content-type content-type-params]
  (join ";" (cons content-type
                  (map (partial join "=") content-type-params))))

(defn- route->str
  "Returns a string from a route suitable for printing."
  [{:keys [content-type content-type-params charset encoding]}]
  (pr-str {"accept" (content-type->str content-type content-type-params)
           "accept-charset" charset
           "accept-encoding" encoding}))

(defn- trim-and-lowercase
  "Returns a trimmed and lowercased s."
  [^String s]
  (.toLowerCase (trim s)))

(defn- split-fn
  "Returns a function that splits a string according to re, then trims
  and lowercases each split."
  [re]
  (fn [s]
    (mapv trim-and-lowercase (split s re 2))))

(defn- parse-header-element
  "Returns a map of :field, :q, and :params from an accept-* header
  element.  Each entry will be trimmed and lowercased."
  [s]
  (let [[field & parameters] (split s #";")
        [[q q-value] & pairs :as all-pairs] (map (split-fn #"=") parameters)
        q? (= q "q")]
    {:field (trim-and-lowercase field)
     :q (if q? (Double/parseDouble q-value) 1.0)
     :params (into {} (if q? pairs (seq all-pairs)))}))

(defn- parse-header-elements
  "Returns a sequence of element maps for header. This can be applied to
  accept, accept-encoding, or accept-charset."
  [header default]
  (map parse-header-element (split (or header default) #",")))

;;; default-charset and default-encoding are the same so we can define
;;; only one function.
(defn- parse-accept-*
  "Returns an ordered sequence of values according to the \"accept-*\"
  header.  RFC-2616 is followed for prioritization.  This can be used
  for \"accept-encoding\" \"accept-charset\" and \"accept-language\"."
  [accept-*]
  (->> (parse-header-elements accept-* charset-wildcard)
       (sort-by :q >)
       (map :field)))

(defn- parse-accept
  "Returns an ordered sequence of maps containing :content-type and
  :content-type-params from an accept header.  RFC-2616 is followed
  for prioritization."
  [accept]
  (->> (parse-header-elements accept content-type-wildcard)
       (sort-by (comp empty? :params))
       (sort-by (fn [{:keys [field]}]
                  (let [[type sub-type] ((split-fn #"/") field)]
                    (+ (if (= type "*") 2 0) (if (= sub-type "*") 1 0)))))
       (sort-by :q >)
       (map (fn [{:keys [field params]}]
              {:content-type field :content-type-params params}))))

(defn routes
  "Returns an ordered sequence of content negotiation routes from the
  \"accept\" \"accept-charset\" and \"accept-encoding\" headers.
  RFC-2616 is followed for prioritization, including the use of the q
  parameter."
  {:arglists '([headers])}
  [{accept "accept"
    accept-charset "accept-charset"
    accept-encoding "accept-encoding"}]
  (for [m (parse-accept accept)
        charset (parse-accept-* accept-charset)
        encoding (parse-accept-* accept-encoding)]
    (route (merge m {:charset charset :encoding encoding}))))

(def default-routes
  "The default set of content negotiation routes that should be
  supported in a route-map."
  (set (for [content-type default-content-types
             charset default-charsets
             encoding default-encodings]
         (route {:content-type content-type
                 :charset charset
                 :encoding encoding}))))

(defn- throw-unhandled-route
  "Throws an ExceptionInfo with :type ::unhandled-route for route."
  [route]
  (throw (ex-info (format "Unhandled route: %s" (pr-str route))
                  {:type ::unhandled-route
                   :route route})))

(defn- stream-writer
  "Returns a function that accepts a clojure object and returns a new
  function that writes the object using printer to an output stream."
  [stream-filter printer]
  (fn [obj]
    (fn [stream]
      (with-open [out (OutputStreamWriter. (stream-filter stream))]
        (binding [*out* out]
          (printer obj))
        (.flush out)))))

(defn- encoding-stream-filter
  "Returns a function that filters an OutputStream according to
  encoding."
  [encoding]
  (case encoding
    "identity" identity
    "gzip" #(GZIPOutputStream. %)
    :else nil))

(defn- content-type-printer
  "Returns a function that prints clojure objects according to
  content-type and content-type-params."
  [content-type content-type-params]
  (case content-type
    "application/edn" pr
    "application/json" json/pprint
    :else nil))

(defn- default-response-fn
  "Returns the default response function to handle route if one exists,
  throws ExceptionInfo with :type ::unhandled-route otherwise."
  [{:keys [content-type content-type-params charset encoding] :as route}]
  (let [stream-filter (encoding-stream-filter encoding)
        printer (content-type-printer content-type content-type-params)]
    (if (and stream-filter printer (= charset "utf-8"))
      (stream-writer stream-filter printer)
      (throw-unhandled-route route))))

(defn route-map
  "Returns a map from a set of content negotiation routes to default
  functions that can be used to write a clojure object to a pedestal
  response body.  When no routes are passed in, default-routes are
  used.  If there is no default function to handle a route passed in,
  an ExceptionInfo with :type ::unhandled-route is raised.  The
  default functions return functions that write to output streams."
  ([]
     (route-map default-routes))
  ([routes]
     (let [routes-seq (seq routes)]
       (zipmap routes-seq (map default-response-fn routes)))))

(defn- not-acceptable
  "Returns a 406 Not Acceptable ring response specifying the received
  accept headers and the list of acceptable routes."
  [routes]
  (let [help-msg "Set your headers according to one of the following:"
        accept-msg (join \newline (cons help-msg (map route->str routes)))]
    (fn [headers]
      {:status 406
       :headers {"Content-Type" "text/plain"}
       :body (join \newline
                   (list "No acceptable resource for:"
                         (pr-str (select-keys headers header-keys))
                         accept-msg))})))

(defn- content-negotiation-enter
  "Returns the enter function for the content-negotiation interceptor."
  [not-acceptable-response-fn wildcard-map route-map]
  (let [replacer (partial replace-wildcards wildcard-map)
        route-set (-> route-map keys set)]
    (fn [{{headers :headers} :request :as context}]
      (if-let [route (->> (routes headers)
                          (map replacer)
                          (some route-set))]
        (assoc-in context [:request ::content-negotiation] route)
        (-> context
            (assoc :response (not-acceptable-response-fn headers))
            terminate)))))

(defn- content-negotiation-leave
  "Returns the leave function for the content-negotiation interceptor."
  [route-map]
  (fn [context]
    (if (< 199 (get-in context [:response :status]) 299)
      (let [route (get-in context [:request ::content-negotiation])]
        (-> context
            (update-in [:response :body] (route-map route))
            (assoc-in [:response :headers "Content-Type"]
                      (content-type->str (:content-type route)
                                         {"charset" (:charset route)}))
            (assoc-in [:response :headers "Content-Encoding"]
                      (:encoding route))))
      context)))

(defn content-negotiation
  "Returns an interceptor that performs content negotiation.

  On enter, it will attach the first supported content negotiation
  route from route-map to ::content-negotiation in request if the
  accept headers can be satisfied.  Otherwise, it attaches a 406 Not
  Acceptable response to the context.  The not-acceptable-response-fn
  returns a ring response and will be passed the request headers as a
  single map argument.

  On leave, it will check the status of the response.  If the status
  is 200, it will update the response body using the function for the
  route found on enter and set the \"Content-Type\" and
  \"Content-Encoding\" headers appropriately, otherwise it will do
  nothing."
  ([]
     (content-negotiation (route-map)))
  ([route-map]
     (content-negotiation default-wildcard-map route-map))
  ([wildcard-map route-map]
     (content-negotiation (not-acceptable (keys route-map))
                          wildcard-map route-map))
  ([not-acceptable-response-fn wildcard-map route-map]
     (around ::content-negotiation
             (content-negotiation-enter not-acceptable-response-fn
                                        wildcard-map route-map)
             (content-negotiation-leave route-map))))
