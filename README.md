# Introduction

Link to ticket: https://clojure.atlassian.net/browse/CLJ-2594


# Starting a Clojure REPL for this project

Start a Clojure REPL with one of the two command lines below,
depending upon whether you wish to use Clojure 1.10.1, or a patched
version of the latest Clojure version (as of 2020-Dec).

```bash
$ clj -A:clj:socket:cljol:collection-check
$ clj -A:clj:socket:cljol:collection-check:clj-patched
```


# Using cljol library to visualize behavior

After starting a REPL, evaluate the entire block of code enclosed in
the `(do ...)` expression below, then continue evaluating further
forms.


```clojure
(do
(require '[cljol.dig9 :as d])
(require '[ubergraph.core :as uber])

;; This is useful when using cljol on objects that contain objects
;; like Java threads, which can have references to all kinds of things
;; in the JVM, which makes the object graphs too big to be useful.

(def stop-fn (proxy [java.util.function.Function] []
               (apply [obj]
                 (cond
                   ;; follow no references of fields of Reference objects
                   (instance? java.lang.ref.Reference obj)
                   {"only-fields-in-set" #{}}

                   (instance? java.lang.Thread obj)
                   {"only-fields-in-set" #{}}

                   (instance? clojure.lang.Namespace obj)
                   {"only-fields-in-set" #{}}

                   (instance? java.lang.Class obj)
                   {"never-fields-in-set" #{"name" "annotationData"
		                            "reflectionData"}}
                   
                   ;; else follow all field with references
                   :else nil))))

(def opts {:stop-walk-fn stop-fn})

(defn graph-no-longs [obj-coll fname opts]
  (let [g (d/sum obj-coll opts)
        g2 (uber/remove-nodes*
	     g (filter (fn [n] (instance? Long (uber/attr g n :obj)))
                       (uber/nodes g)))]
    (d/view-graph g2)
    (d/view-graph g2 {:save {:filename fname :format :dot}})))
)
```

With unmodified Clojure 1.10.1 (and probably all released Clojure
versions back since transients were introduced), the following drawing
shows that while `v1` and `v2`, both vectors with at most 32 elements,
both point at `empty-singleton-node`, `v1b` has allocated its own
copy.

```clojure
(def empty-singleton-node clojure.lang.PersistentVector/EMPTY_NODE)
(def v1 [0])
(def v2 [-1])
(def v1b (persistent! (transient v1)))

(d/view [empty-singleton-node v1 v2 v1b])

(def d1 (d/sum [ [[1]] ]))
(def d2 (d/sum [ (persistent! (transient [[1]])) ]))
```

This behavior is according to the current implementation of class
clojure.lang.PersistentVector$TransientVector, because method
asTransient will always allocate a fresh root node, even for short
vectors, so it has a place to store the freshly allocated value that
it assigns to that root node's field 'edit', that is used by
transients to distinguish tree nodes "owned" by this transient vector
(i.e. guaranteed to only be reached by following a chain of references
starting with this transient instance's root node), versus those that
might be shared with other persistent vectors.

The following tests below were used to spot-check that a proposed
patch to Clojure 1.10.2-alpha4 operates correctly when a transient
increases in size from 32 to 33 elements, which is the point where the
root node should be copied, and the copy become owned by this
transient vector.

```clojure
(def v1tplus31 (reduce conj! (transient v1) (range 1 32)))
(def v1tplus32 (reduce conj! (transient v1) (range 1 33)))

(graph-no-longs [empty-singleton-node v1 v2 v1tplus31] "tmp.dot" opts)
(graph-no-longs [empty-singleton-node v1 v2 v1tplus31 v1tplus32] "tmp.dot" opts)

(def v33 (persistent! v1tplus32))

(graph-no-longs [empty-singleton-node (vec (range 33)) v33] "tmp.dot" opts)
```


# Change to Clojure 1.10.2-alpha4 that reduce memory in this scenario

"This scenario", meaning situations where a transient vector is
created from a persistent vector with at most 32 elements, modified in
ways where it always remains under 32 elements, and is the changed
into a persistent vector.

The approach taken with the patch named
`patches/clojure-1.10.2-alpha4-v1.patch` is to add a new field named
`edit` to class `TransientVector`, which is used everywhere in the
code dealing with transient vectors, in places where `root.edit` was
being used.

When a transient vector is first created, it will always share the
same identical `root` node in memory with the persistent vector from
which it was created.  That persistent vector's root node should have
`edit` pointing at an AtomicReference that contains `nil`, and thus
like any other tree nodes that are its children, grandchildren, etc.,
with that same `nil` AtomicReference, it is considered shared, not
owned by this TransientVector instance.  If the TransientVector
instance ever wants to modify the root node, it will make a copy
first, just as it does for any other non-root node in the tree.


# Using `collection-check` library to test changes to `PersistentVector`

Start a Clojure REPL with one of the two command lines below,
depending upon whether you wish to use Clojure 1.10.1, or a patched
version of the latest Clojure version (as of 2020-Dec).

```bash
$ clj -A:clj:socket:cljol:collection-check
$ clj -A:clj:socket:cljol:collection-check:clj-patched
```

```clojure
(require '[collection-check.core :as ccheck])
(require '[clojure.test.check.generators :as gen])

(ccheck/assert-vector-like 1e3 [] gen/int)
(ccheck/assert-vector-like 1e4 [] gen/int)
```

If `assert-vector-like` passes, it simply computes for a while,
running many tests, and eventually returns nil.


# License

Copyright © 2020 Andy Fingerhut

This program and the accompanying materials are made available under
the terms of the Eclipse Public License 1.0 which is available at
http://www.eclipse.org/org/documents/epl-v10.html

