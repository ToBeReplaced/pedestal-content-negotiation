(defproject pedestal-content-negotiation "0.3.1"
  :description "Content negotiation for Pedestal web services."
  :url "https://github.com/ToBeReplaced/pedestal-content-negotiation"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [io.pedestal/pedestal.service "0.3.0"
                  :exclusions [cheshire org.clojure/core.incubator
                               ring/ring-core]]
                 [org.clojure/data.json "0.2.5"]]
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort)
