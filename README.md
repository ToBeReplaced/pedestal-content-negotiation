# pedestal-content-negotiation

Content negotiation for Pedestal web services.

NOTICE: I am no longer actively developing this library.  There is
work that needs to be accomplished, such as making it easier to extend
to new content-types.  Consequently, I am opening this up to requests
for a change of ownership.  Someone with more involved
content-negotiation needs would be a better steward than I can be
moving forward.

## Quickstart

The default interceptor will accept:

```
Accept: application/edn, application/json, application/*, */*
Accept-Charset: utf-8, *
Accept-Encoding: gzip, identity, *
```

If you add the interceptor to your route, the output body will be written according to the content-type, charset, and encoding.

```clojure
(defn foo
  "Returns a ring response with a clojure object as its body (not a string)."
  [request]
  {:status 200 :body {:foo 1}})

(defroutes routes
  [[["/foo" {:get foo}
     ^:interceptors [(content-negotiation/content-negotiation)]]]])
```

## Supported Clojure Versions

pedestal-content-negotiation is tested on Clojure 1.6.0 only.

## Maturity

This is alpha level software.

## Installation

pedestal-content-negotiation is available as a Maven artifact from [Clojars]:
```clojure
[pedestal-content-negotiation "0.4.0"]
```
pedestal-content-negotiation follows [Semantic Versioning].  Please note that this means the public API is not yet considered stable, and so it is subject to change.

## Documentation

[Codox API Documentation]

## Usage

```clojure
;;; Require the library
user> (require '[pedestal.content-negotiation :as content-negotiation])
;;; For the rest of the examples.
user> (require '[clojure.pprint :refer [pprint]])
```

Routes are the basic data structure of content negotiation.  A route is a map containing `:content-type` `:content-type-params` `:charset`, and `:encoding`.  When you are encoding a response entity, you need to match a route to a function.

```clojure
;;; Example route, created using the route function.
user> (pprint (content-negotiation/route {}))
{:content-type "*/*",
 :content-type-params {},
 :charset "*",
 :encoding "*"}
nil
```

The various `*` values indicate wildcards, since no values were specified for those keys.  Although it is rare that you would need to do this directly, wildcards can be replaced with `replace-wildcards`.  This function accepts an optional `wildcard-map` which can be used to change the default values of the wildcards.

```clojure
user> (pprint (content-negotiation/replace-wildcards (content-negotiation/route {})))
{:content-type "application/edn",
 :content-type-params {},
 :charset "utf-8",
 :encoding "identity"}
nil
```

The library exports an interceptor `content-negotiation`.  This can be used to encode response entities outside of your ring handlers.  The main argument to the interceptor is a "route-map".  A route-map is a map from content negotiation routes to functions that encode clojure objects.  A route-map should not contain routes for any wildcards, as a wildcard-map will be used to replace wildcards with default values.  This helps keep your route-map concise and readable.  The `route-map` function can be used to create a route-map using the default functions in this library.  Alternatively, you can build one yourself from the set of routes you would like to support.

When a request enters `content-negotiation`, it's "Accept", "Accept-Charset", and "Accept-Encoding" request headers are parsed into an ordered sequence of content negotiation routes to compare against by following [RFC 2616].  You can do this directly via the `routes` function.

```clojure
user> (pprint (content-negotiation/routes {"accept" "application/edn,application/json;q=0.5"}))
({:content-type "application/edn",
  :content-type-params {},
  :charset "*",
  :encoding "*"}
 {:content-type "application/json",
  :content-type-params {},
  :charset "*",
  :encoding "*"})
nil
```

Notice that the q-parameter dictated which content-type is prioritized.  Each of these routes will be converted using the wildcard-map and then checked against the route-map to see if there is a match.  The first match will be attached to the ring request as ::content-negotiation.  If there is no match, a 406 Not Acceptable ring response is attached to the context.

When the `:leave` event of the interceptor is triggered, if the context's response has a status in the 2xx range, it will update the response body using the function for the matched route and set the "Content-Type" and "Content-Encoding" headers accordingly.

## Changelog

### v0.4.0

- Update to use pedestal 0.3.0.

### v0.3.1

- Fix missing clojure.data.json dependency.
- Remove extra dependencies inherited from pedestal-service.

### v0.3.0

- Negotiate responses with status 2xx instead of just 200.

### v0.2.0

- Add gzip to accepted default encodings.

### v0.1.0

- Initial Release

## Support

Contact ToBeReplaced on IRC at #clojure or #pedestal with any questions.  Alternatively send an email to the [pedestal-users] Google group.

## License

Copyright © 2013 ToBeReplaced

Distributed under the Eclipse Public License, the same as Clojure.  The license can be found at epl-v10.html in the root of this distribution.


[Clojars]: http://clojars.org/pedestal-content-negotiation
[Semantic Versioning]: http://semver.org
[Codox API Documentation]: http://ToBeReplaced.github.com/pedestal-content-negotiation
[doc]: http://tobereplaced.github.io/pedestal-content-negotiation/pedestal.content-negotiation.html#var-content-negotiation
[RFC 2616]: http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.1
[pedestal-users]: https://groups.google.com/forum/#!forum/pedestal-users
