(defproject pedestal-content-negotiation "0.3.1-SNAPSHOT"
  :description "Content negotiation for Pedestal web services."
  :url "https://github.com/ToBeReplaced/pedestal-content-negotiation"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.pedestal/pedestal.service "0.2.1"
                  :exclusions [cheshire org.clojure/core.incubator
                               ring/ring-core]]
                 [org.clojure/data.json "0.2.3"]]
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort)
