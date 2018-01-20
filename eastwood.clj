(disable-warning
 {:linter :suspicious-expression
  :if-inside-macroexpansion-of #{'clojure.spec.alpha/def
                                 'clojure.spec.alpha/fdef
                                 'clojure.spec.alpha/coll-of
                                 'clojure.spec.alpha/nilable
                                 'clojure.spec.alpha/keys
                                 'clojure.spec.alpha/keys*
                                 'clojure.spec.alpha/and}
  :within-depth 20
  :reason "standard usage triggers this"})

(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'sysrev.web.app/wrap-permissions
                                 'sysrev.db.core/with-transaction}
  :within-depth 10
  :reason ""})
