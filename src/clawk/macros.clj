(ns clawk.macros)

(defmacro some-real-fn->
  "When expr is not nil threads it into the first form (via ->),
  and when that result is not nil, through the next etc.

  Filters any form that is the identity fn."
  [expr & forms]
  (let [g (gensym)
        pstep (fn [step] `(if (nil? ~g) nil (-> ~g ~step)))]
     `(let [~g ~expr
            ~@(interleave (repeat g)
                          (mapv pstep
                               (filterv #(not= identity %) forms)))]
        ~g)))

(defmacro some-real-fn->>
  "When expr is not nil and the step fn is not identity,
  threads it into the first form (via ->),
  and when that result is not nil, through the next etc"
  [expr & forms]
  (let [g (gensym)
        pstep (fn [step] `(if (nil? ~g) nil (->> ~g ~step)))]
     `(let [~g ~expr
            ~@(interleave (repeat g)
                          (mapv pstep
                               (filterv #(not= identity %) forms)))]
        ~g)))
