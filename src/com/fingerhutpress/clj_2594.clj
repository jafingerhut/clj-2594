(ns com.fingerhutpress.clj-2594
  (:require [clojure.string :as str]))

(defn print-cp []
  (let [cp (get (System/getProperties) "java.class.path")]
    (doseq [p (str/split cp #":" -1)]
      (println p))))

(defn summarize-actions [actions]
  (if-not (sequential? actions)
    {:problem :actions-not-sequential, :actions actions}
    (if-not (every? sequential? actions)
      {:problem :actions-elem-not-sequential, :actions actions}
      (let [acts (apply concat actions)]
        (if-not (every? vector? acts)
          {:problem :action-not-vector, :actions actions}
          {:problem :none,
           :action-freq (into (sorted-map) (frequencies (map first acts)))})))))

(defn summarize-args [args]
  (if-not (= 1 (count args))
    {:problem :args-not-length-1, :count (count args), :args args}
    (let [arg1 (first args)]
      (if-not (map? arg1)
        {:problem :arg1-not-map, :class (class arg1), :arg1 arg1}
        (let [ks (set (keys arg1))]
          (if-not (= ks #{'actions})
            {:problem :arg1-map-unexpected-keys, :data ks, :arg1 arg1}
            (let [arg1-actions (get arg1 'actions)]
              (summarize-actions arg1-actions))))))))

(defn abbrev-args-summary [args-sum]
  (if (= :none (:problem args-sum))
    (:action-freq args-sum)
    args-sum))

(defn sum-args [args]
  (if (nil? args)
    nil
    (abbrev-args-summary (summarize-args args))))

(def last-failing-args-seen (atom nil))
(def all-failing-args-seen (atom #{}))
(def num-times-failing-args-seen-before (atom 0))
(def all-passing-args-seen (atom #{}))
(def num-times-passing-args-seen-before (atom 0))

(defn my-report-fn-verbose-reset! []
  (reset! last-failing-args-seen nil)
  (reset! all-failing-args-seen #{})
  (reset! num-times-failing-args-seen-before 0)
  (reset! all-passing-args-seen #{})
  (reset! num-times-passing-args-seen-before 0))

(defn my-report-fn-verbose [m]
  (case (:type m)
    :trial
    (when (zero? (mod (:num-tests m) 1000))
      (let [{:keys [args num-tests num-tests-total pass? property result
                    result-data seed]} m
            abbrev-args-sum (abbrev-args-summary (summarize-args args))]
        (print "trial "
               (format "%d/%d" num-tests num-tests-total)
               (if pass? "pass" "fail")
               result
               "r-d:" result-data)
        (print " sum:" abbrev-args-sum)
        ;;(print " args:" args)
        ;;(print " m:" m)
        (println)))

    :complete
    (let [{:keys [num-tests pass? property result seed time-elapsed-ms]} m]
      (print (name (:type m)) " ")
      (println (if pass? "pass" "fail")
               "in" time-elapsed-ms " msec"
               "result: " result))

    :failure
    (let [{:keys [fail failed-after-ms failing-size num-tests pass? property
                  result result-data seed]} m
          abbrev-fail-sum (abbrev-args-summary (summarize-args fail))]
      (reset! last-failing-args-seen fail)
      (print (name (:type m)) " ")
      (print "num-tests:" num-tests
             "failing-size:" failing-size
             "fail-sum:" abbrev-fail-sum
             "result-data:" result-data)
      ;;(print " keys:" (sort (keys m)))
      ;;(print " m:" m)
      (println))

    :shrink-step
    (let [{:keys [args depth pass? result result-data smallest
                  total-nodes-visited]} (:shrinking m)]
      (when (>= total-nodes-visited 5000)
        (throw (ex-info "shrinking took too long" {})))
      (if pass?
        (if (contains? @all-passing-args-seen args)
          (swap! num-times-passing-args-seen-before inc)
          (swap! all-passing-args-seen conj args))
        ;; else failed
        (if (contains? @all-failing-args-seen args)
          (swap! num-times-failing-args-seen-before inc)
          (swap! all-failing-args-seen conj args)))
      (when (zero? (mod (:total-nodes-visited (:shrinking m))
                        1000))
        (let [abbrev-args-sum (abbrev-args-summary (summarize-args args))
              smallest-args-sum (abbrev-args-summary (summarize-args smallest))]
          (print (name (:type m)) " ")
          (print (if pass? "pass" "fail")
                 total-nodes-visited "nodes")
          (when (and (not (nil? @last-failing-args-seen))
                     (= args @last-failing-args-seen))
            (println)
            (print "    args same as last failing ones seen"))
          (when (= args smallest)
            (println)
            (print "    args is same as smallest"))
          (println)
          (print "       args-sum:" abbrev-args-sum)
          (println)
          (print "   smallest-sum:" smallest-args-sum)
          ;;(print " m:" m)
          (reset! last-failing-args-seen smallest)
          (println))))

    :shrunk
    (let [{:keys [type]} m]
      (print (name (:type m)) " ")
      (print " keys:" (sort (keys m)))
      (print " m:" m)
      (println))))

(def shrinking-pass-count (atom 0))
(def shrinking-fail-count (atom 0))
(def smallest-failing-args-seen (atom nil))

(defn my-report-fn-reset! []
  (reset! shrinking-pass-count 0)
  (reset! shrinking-fail-count 0)
  (reset! smallest-failing-args-seen nil))

(defn my-report-fn [m]
  (case (:type m)
    :trial
    (when (zero? (mod (:num-tests m) 1000))
      (let [{:keys [args num-tests num-tests-total pass? property result
                    result-data seed]} m]
        (println "trial " (format "%d/%d" num-tests num-tests-total))))

    :complete
    (let [{:keys [num-tests pass? property result seed time-elapsed-ms]} m]
      (println "complete" (if pass? "pass" "fail") "in" time-elapsed-ms "msec"
               "seed" seed))

    :failure
    (let [{:keys [fail failed-after-ms failing-size num-tests pass? property
                  result result-data seed]} m]
      (reset! smallest-failing-args-seen fail)
      (println "failure"
               "seed" seed
               "num-tests" num-tests
               "failing-size" failing-size
               "result-data" result-data))

    :shrink-step
    (let [{:keys [num-tests failing-size seed shrinking]} m
          {:keys [args depth pass? result result-data smallest
                  total-nodes-visited]} shrinking
          shrinking-too-long? (>= total-nodes-visited 5000)]
      (if pass?
        (swap! shrinking-pass-count inc)
        (swap! shrinking-fail-count inc))
      (when (or shrinking-too-long?
                (and (zero? (mod total-nodes-visited 1000))
                     (> total-nodes-visited 0)))
        (println "shrink-step"
                 total-nodes-visited "nodes"
                 @shrinking-pass-count "shrink tests passed and"
                 @shrinking-fail-count "failed"
                 (if shrinking-too-long?
                   (str " seed " seed
                        " throwing exception for shrinking too long")
                   ""))
        (flush)
        (when shrinking-too-long?
          (reset! smallest-failing-args-seen smallest)
          (throw (ex-info "shrinking took too long" {})))))

    :shrunk
    (let [{:keys [type]} m]
      (println "shrunk"))))
