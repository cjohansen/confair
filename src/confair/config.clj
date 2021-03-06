(ns confair.config
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [confair.edn-loader :as edn-loader]
            [taoensso.nippy :as nippy])
  (:import java.util.Base64))

(defn merge-config [sources]
  (when-let [s (seq (filter #(:dev-config/import (meta (:config %))) sources))]
    (throw (ex-info "Recursively importing config is not supported, because it is not recommended."
                    {:sources s})))
  (let [config (apply merge (map :config sources))]
    (with-meta
      config
      (assoc (meta config) :config/key-sources
             (into {} (for [k (keys config)]
                        [k (->> sources
                                (filter #(contains? (:config %) k))
                                last
                                :file)]))))))

(defn encrypt [form password]
  (nippy/freeze-to-string form {:password [:cached password]}))

(defn decrypt [encrypted password]
  (nippy/thaw-from-string encrypted {:password [:cached password]}))

(defn reveal [config secrets]
  (let [get-secret #(and (vector? %)
                         (get secrets (first %)))]
    (with-meta
      (into {} (for [[k val] config]
                 [k (if-let [secret (get-secret val)]
                      (try (decrypt (second val) secret)
                           (catch Exception e
                             (throw (ex-info (str "Unable to decrypt config key " k)
                                             {:k k :val val} e))))
                      val)]))
      (assoc (meta config) :config/encrypted-keys
             (into {} (keep (fn [[k v]] (when (get-secret v) [k (first v)]))
                            config))))))

(defn get-env [k]
  (System/getenv k))

(defn resolve-secret [k secret]
  (str/trim
   (cond
     (string? secret)
     secret

     (and (vector? secret) (= :config/file (first secret)))
     (let [f (io/file (second secret))]
       (if (.exists f)
         (if (.isFile f)
           (slurp f)
           (throw (ex-info (str "Expected file, got directory " (second secret) " for " k)
                           {k secret})))
         (throw (ex-info (str "Unknown file " (second secret) " for " k)
                         {k secret}))))

     (and (vector? secret) (= :config/env (first secret)))
     (or (get-env (second secret))
         (throw (ex-info (str "Unknown env variable " (second secret) " for " k)
                         {k secret})))

     :else (throw (ex-info (str "Unknown secret type for " k) {k secret})))))

(defn resolve-secrets [secrets]
  (into {}
        (for [[k secret] secrets]
          [k (resolve-secret k secret)])))

(defn- mask-str [s extra]
  (str
   (cond
     (< 8 (count s)) (str (subs s 0 (+ 2 extra)) (subs "*****" extra))
     (< 3 (count s)) (str (subs s 0 (+ 1 extra)) (subs "******" extra))
     :else "*******")
   (if (< 5 (count s)) (last s) "*")))

(defn mask-secret [v]
  (cond
    (string? v)  [:config/masked-string (mask-str v 0)]
    (number? v)  [:config/masked-number (mask-str (str v) 0)]
    (boolean? v) [:config/masked-boolean "********"]
    (vector? v)  [:config/masked-vector (mask-str (str v) 1)]
    (map? v)     [:config/masked-map (mask-str (str v) 2)]
    (set? v)     [:config/masked-set (mask-str (str v) 2)]
    (list? v)    [:config/masked-list (mask-str (str v) 1)]
    :else        [:config/masked-value (mask-str (str v) 0)]))

(defn mask-secrets [config]
  (let [secret-k? (:config/encrypted-keys (meta config) {})]
    (into {}
          (for [[k v] config]
            [k (cond-> v
                 (secret-k? k)
                 (mask-secret))]))))

(deftype MaskedConfig [^clojure.lang.IPersistentMap config]
  Object
  (toString [_]           (str (mask-secrets config)))
  (hashCode [_]           (.hashCode [::masked-config config (:config/encrypted-keys (meta config))]))
  (equals [_ o]           (and (instance? MaskedConfig o)
                               (.equiv config (.config o))
                               (.equiv (:config/encrypted-keys (meta config))
                                       (:config/encrypted-keys (meta (.config o))))))

  clojure.lang.IMeta
  (meta [_]               (.meta config))

  clojure.lang.IPersistentMap
  (count [_]              (.count config))
  (empty [_]              (MaskedConfig. (.empty config)))
  (cons  [_ x]            (MaskedConfig. (.cons config x)))
  (equiv [this o]         (.equals this o))
  (containsKey [_ k]      (.containsKey config k))
  (entryAt [_ k]          (.entryAt config k))
  (seq [_]                (.seq config))
  (iterator [_]           (.iterator config))
  (assoc [_ k v]          (MaskedConfig. (assoc config k v)))
  (without [_ k]          (MaskedConfig. (dissoc config k)))

  clojure.lang.ILookup
  (valAt [_ k]            (config k))
  (valAt [_ k not-found]  (config k not-found))

  clojure.lang.IFn
  (invoke [_ k]           (config k))
  (invoke [_ k not-found] (config k not-found)))

(defmethod print-method MaskedConfig [m w]
  (.write w (str m)))

(defmethod clojure.pprint/simple-dispatch MaskedConfig [m]
  (.write *out* (with-out-str (pprint (mask-secrets (.config m))))))

(defn mask-config [config]
  (MaskedConfig. config))

(defn from-file [path & [secrets-override]]
  (let [raw-config (edn-loader/load-one path)
        config (merge-config (concat (for [f (:dev-config/import (meta raw-config))]
                                       {:file f :config (edn-loader/load-one f)})
                                     [{:file path :config (vary-meta raw-config dissoc :dev-config/import)}]))
        secrets (resolve-secrets (or secrets-override
                                     (:config/secrets (meta raw-config))))]
    (vary-meta (reveal config secrets)
               assoc
               :config/secrets secrets
               :config/from-file path)))

(defn from-string [s source & [secrets-override]]
  (let [config (edn-loader/load-one-str s source)
        secrets (resolve-secrets (or secrets-override
                                     (:config/secrets (meta config))))]
    (vary-meta (reveal config secrets)
               dissoc :config/secrets)))

(defn from-base64-string [s source & [secrets-override]]
  (from-string (String. (.decode (Base64/getDecoder) s)) [:base64-string source] secrets-override))

(defn from-env [env-var & [secrets-override]]
  (from-string (get-env env-var) [:env env-var] secrets-override))

(defn from-base64-env [env-var & [secrets-override]]
  (from-base64-string (get-env env-var) [:env env-var] secrets-override))
