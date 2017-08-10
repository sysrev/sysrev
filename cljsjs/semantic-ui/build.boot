(set-env!
 :resource-paths #{"resources"}
 :dependencies '[[cljsjs/boot-cljsjs "0.5.2"  :scope "test"]
                 [cljsjs/jquery "3.2.1-0"]])

(require '[cljsjs.boot-cljsjs.packaging :refer :all])

(def +lib-version+ "2.2.11")
(def +version+ (str +lib-version+ "-0"))

(task-options!
 pom  {:project     'org.clojars.jeffwk/semantic-ui
       :version     +version+
       :description "Semantic UI jquery behaviors."
       :url         "http://semantic-ui.com"
       :scm         {:url "https://github.com/cljsjs/packages"}
       :license     {"MIT" "http://opensource.org/licenses/MIT"}})

(task-options! push {:repo "clojars"})

(deftask package []
  (comp
   (download
    :url (format "https://github.com/Semantic-Org/Semantic-UI/archive/%s.zip" +lib-version+)
    :checksum "5B60D37F15986659E541F9ABE0FDA316"
    :unzip true)
   (sift :move {#"^Semantic-UI-.*/dist/semantic.js$"     "cljsjs/semantic-ui/development/semantic.inc.js"
                #"^Semantic-UI-.*/dist/semantic.min.js$" "cljsjs/semantic-ui/production/semantic.min.inc.js"
                #"^Semantic-UI-.*/dist/([^/]+\.css)$"    "cljsjs/semantic-ui/common/$1"
                #"^Semantic-UI-.*/dist/themes/(.*)$"     "cljsjs/semantic-ui/common/themes/$1"})
   (sift :include #{#"^cljsjs"})
   (deps-cljs
    :name "cljsjs.semantic-ui"
    :requires ["cljsjs.jquery"])
   (pom)
   (jar)))