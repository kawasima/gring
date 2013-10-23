(defproject net.unit8/gring "0.1.0"
  :description "Git server on ring."
  :url "https://github.com/kawasima/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [ [org.clojure/clojure "1.5.1"]
                  [compojure "1.1.5"]
                  [clj-jgit "0.6.1"] ;; git
                  ]
  :plugins [ [lein-ring "0.8.2"] ]
  :ring {:handler gring.core/app}

)
