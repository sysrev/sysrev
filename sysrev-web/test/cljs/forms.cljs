(ns sysrev-web.test.forms
  (require sysrev-web.forms.validate :refer [validate]))


(def login-validation {:user [not-empty "Must provide user"]
                       :password [#(> (count %) 6) (str "Password must be greater than six characters")]})



(def form-data {:user "Hi", :password "pw"})

(assert (= (:password (validate form-data login-validation)) (second (:password login-validation))))
