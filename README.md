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
shows that `v1` and `v2`, vectors with at most 32 elements, both point
at `empty-singleton-node`, `v1b` has allocated its own copy.

There are 3 gray nodes in the drawing.  The two gray nodes that both
have an edge to the third gray node are `v1` and `v2`.  The gray node
that they both point at is the object `empty-singleton-node`.

```clojure
(def empty-singleton-node clojure.lang.PersistentVector/EMPTY_NODE)
(def v1 [0])
(def v2 [-1])

(d/view [empty-singleton-node v1 v2])
```

The drawing created below shows that `v1` and `v1b` do _not_ both
point at `empty-singleton-node`.  Vector `v1b` has its own copy of the
Java array referenced in the `array` field of its `root` node, but
like the array for `v1`, both of these arrays contain 32 `nil`
references.

```clojure
(def v1b (persistent! (transient v1)))
(d/view [empty-singleton-node v1 v1b])
```

This output shows the total size of all JVM objects referenced by a
chain of references, starting at `v1` or `v1b`.

```clojure
(def tmp (d/sum [v1 v2]))

;; The total size of v1 and v2 is 360 bytes, counting their shared
;; empty-singleton-node reference only once, since it only occupies
;; memory once no matter how many times it is referenced.

(def tmp (d/sum [v1 v1b]))

;; The total size of v1 and v1b is 520 bytes, since v1b has a copy of
;; empty-singleton-node.


;; These vectors are from the original description of the Clojure
;; ticket CLJ-2594

(def v3 [[1]])
(def v3b (persistent! (transient v3)))

(d/view [empty-singleton-node v3 v3b])
(def tmp (d/sum [v3]))
(def tmp (d/sum [v3b]))
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


# Basic testing

Clojure 1.10.2-alpha4 modified with the patch
`patches/clojure-1.10.2-alpha4-v1.patch` passes all tests included
with Clojure.

From evaluating the expressions shown above, the patched code
successfully avoids allocating a new root node when a transient vector
is created from a persistent vector with at most 32 elements.

No attempt has been made to check if a transient vector is reduced in
size from 33 or more elements, down to 32 elements, and changing its
root node to point at the common singleton EMPTY_NODE.


# Using `collection-check` library to test changes to `PersistentVector`

Note: With a call like `collection-check.core/assert-vector-like 1e3
[] gen/int)` as shown below, it is comparing the behavior of
PersistentVector and TransientVector _against itself_.  Thus if no
exceptions are thrown, the tests are likely to always pass, since the
only thing you are testing is that the same implementation gives the
same result when you perform the same sequence of operations on both.
You cannot catch many bugs in an implementation of
clojure.lang.PersistentVector or clojure.lang.TransientVector using
such a call.

```clojure
(require '[collection-check.core :as ccheck])
(require '[clojure.test.check.generators :as gen])

(time (ccheck/assert-vector-like 1e3 [] gen/int))
```

If `assert-vector-like` passes, it simply computes for a while,
running many tests, and eventually returns nil.

In order to compare an alternate implementation of PersistentVector
and/or TransientVector against the original, it is necessary to give
different class names for the modified versions.

TBD: Try creating a patch with the proposed modified version of
TransientVector as a different class name.


# License

Copyright © 2020 Andy Fingerhut

This program and the accompanying materials are made available under
the terms of the Eclipse Public License 1.0 which is available at
http://www.eclipse.org/org/documents/epl-v10.html

