(ns sysrev.shared.charts)

;; Paul Tol colors: https://personal.sron.nl/~pault/
;;
;; This vector was copied from:
;; https://github.com/google/palette.js/blob/master/palette.js (it is
;; under an MIT license)
;;
;; A working demo of color selections:
;; http://google.github.io/palette.js/
;;
;; which in turn is a reproduction of Paul Tol's work at:
;; https://personal.sron.nl/~pault/colourschemes.pdf
;;
;; Paul developed this palette for scientific charts to clearly
;; differentiate colors and to be color-blind safe
(def paul-tol-colors
  [["#4477aa"],
   ["#4477aa", "#cc6677"],
   ["#4477aa", "#ddcc77", "#cc6677"],
   ["#4477aa", "#117733", "#ddcc77", "#cc6677"],
   ["#332288", "#88ccee", "#117733", "#ddcc77", "#cc6677"],
   ["#332288", "#88ccee", "#117733", "#ddcc77", "#cc6677", "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#ddcc77", "#cc6677", "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77", "#cc6677",
    "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77", "#cc6677",
    "#882255", "#aa4499"],
   ["#332288", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77", "#661100",
    "#cc6677", "#882255", "#aa4499"],
   ["#332288", "#6699cc", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77",
    "#661100", "#cc6677", "#882255", "#aa4499"],
   ["#332288", "#6699cc", "#88ccee", "#44aa99", "#117733", "#999933", "#ddcc77",
    "#661100", "#cc6677", "#aa4466", "#882255", "#aa4499"]])

(defn get-color-palette [item-count]
  (or (first (filter #(>= (count %) item-count)
                     paul-tol-colors))
      (last paul-tol-colors)))

(defn palette-lookup [palette i]
  (nth palette (mod i (count palette))))
