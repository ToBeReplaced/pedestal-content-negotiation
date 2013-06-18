(ns pedestal.content-negotiation-test
  (:require (clojure (test :refer [deftest testing is])
                     (string :refer [join]))
            (pedestal (content-negotiation :refer [route
                                                   routes
                                                   default-routes
                                                   replace-wildcards
                                                   route-map
                                                   content-negotiation])))
  (:import (clojure.lang ExceptionInfo)
           (java.io ByteArrayOutputStream)))

(deftest route-rest
  (testing "should fill in default values"
    (is (= {:content-type "*/*" :content-type-params {}
            :charset "*" :encoding "*"}
           (route {})))))

(deftest routes-test
  (testing "should pass stress test"
    (is (= (let [basic-route (fn [s] (route {:content-type s}))
                 base-routes (list (route {:content-type "foo/bar"
                                           :content-type-params {"baz" "spam"}})
                                   (basic-route "foo/bar")
                                   (basic-route "foo/baz")
                                   (basic-route "spam/*")
                                   (basic-route "foo/*")
                                   (basic-route "*/*"))]
             (interleave (map #(assoc % :charset "utf-8" :encoding "identity")
                              base-routes)
                         (map #(assoc % :charset "utf-8") base-routes)
                         (map #(assoc % :encoding "identity") base-routes)
                         base-routes))
           (routes {"accept" (join " , " ["*/*;q=0.2"
                                          "foo/*;    q=0.2"
                                          "spam/*; q=0.5"
                                          "foo/baz; q    =   0.8"
                                          "foo/bar"
                                          "foo/bar;baz=spam"])
                    "accept-charset" "utf-8, *"
                    "accept-encoding" "identity, *"})))))

(deftest default-routes-test
  (testing "the default route is in default-routes after conversion"
    (is (contains? default-routes (replace-wildcards (route {}))))))

(deftest replace-wildcards-test
  (testing "should replace wildcards with defaults"
    (is (= {:content-type "application/edn" :content-type-params {}
            :charset "utf-8" :encoding "identity"}
           (replace-wildcards (route {}))))))

(defn- output-str
  [stream-printer]
  (let [stream (ByteArrayOutputStream.)]
    (stream-printer stream)
    (str stream)))

(deftest default-route-map-test
  (letfn [(output [m]
            (-> {:foo 1}
                ((-> m route replace-wildcards ((route-map))))
                output-str))]
    (testing "default route should write edn"
      (is (= "{:foo 1}" (output {}))))
    (testing "application/json should write json"
      (is (= "{\"foo\":1}" (output {:content-type "application/json"})))))
  (testing "bad route should throw ::unhandled-route"
    (is (= :pedestal.content-negotiation/unhandled-route
           (try
             (route-map [(replace-wildcards (route {:charset "foo"}))])
             (catch ExceptionInfo e
               (:type (ex-data e))))))))

(def ^:private queue :io.pedestal.service.impl.interceptor/queue)

(defn- app [{response :response queue queue :as context}]
  (if queue
    (-> context
        (update-in [:response :status] #(or % 200))
        (assoc-in [:response :body] {:foo 1}))
    context))

(deftest content-negotiation-test
  (let [interceptor (content-negotiation)]
    (letfn [(context-response [context]
              (-> context
                  (assoc queue 1)
                  ((:enter interceptor))
                  app
                  ((:leave interceptor))
                  (get :response)))
            (response [headers]
              (-> {:request {:headers headers} queue 1}
                  context-response
                  (update-in [:body] output-str)))]
      (testing "2xx status codes are accepted"
        (is (fn? (:body (context-response {:request {:headers {}}
                                           :response {:status 201 :body []}})))))
      (testing "non-2xx status codes are passed through on leave"
        (is (= {:foo 1}
               (:body (context-response {:request {:headers {}}
                                         :response {:status 404 :body []}})))))
      (testing "should send 406 on bad \"accept\" header"
        (let [m (context-response {:request {:headers {"accept" "foo"}}})]
          (is (= 406 (:status m)))
          (is (.startsWith ^String (:body m) "No acceptable resource"))
          (is (= "text/plain" (get-in m [:headers "Content-Type"])))))
      (testing "should use the the negotiator you specify"
        (testing "edn body"
          (is (= "{:foo 1}" (:body (response {})))))
        (testing "edn content-type"
          (is (= "application/edn;charset=utf-8"
                 (get-in (response {}) [:headers "Content-Type"]))))
        (testing "json"
          (is (= "{\"foo\":1}"
                 (:body (response {"accept" "application/json"})))))
        (testing "json content-type"
          (is (= "application/json;charset=utf-8"
                 (get-in (response {"accept" "application/json"})
                         [:headers "Content-Type"])))))
      (testing "content-encoding"
        (testing "gzip Content-Encoding"
          (is (= "gzip"
                 (get-in (response {"accept-encoding" "gzip"})
                         [:headers "Content-Encoding"]))))
        (testing "gzip encoding is compressed"
          (is (not= "{:foo 1}" (:body (response {"accept-encoding" "gzip"})))))
        (testing "default is identity \"Content-Encoding\""
          (is (= "identity"
                 (get-in (response {}) [:headers "Content-Encoding"]))))))))
