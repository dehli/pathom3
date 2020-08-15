(ns com.wsscode.pathom3.interface.smart-map
  "Smart map is a Pathom interface that provides a map-like data structure, but this
  structure will realize the values as the user requests then. Values realized are
  cached, meaning that subsequent lookups are fast."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [<- => >def >defn >fdef ? |]]
    [com.wsscode.misc.core :as misc]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.planner :as pcp]
    [com.wsscode.pathom3.connect.runner :as pcr]
    [com.wsscode.pathom3.entity-tree :as p.ent]
    [com.wsscode.pathom3.format.shape-descriptor :as pfsd]
    #?(:clj [potemkin.collections :refer [def-map-type]])))

(declare smart-map)

(defn wrap-smart-map
  "If x is a composite data structure, return the data wrapped by smart maps."
  [env x]
  (cond
    (map? x)
    (smart-map env x)

    (sequential? x)
    (map #(wrap-smart-map env %) x)

    (set? x)
    (into #{} (map #(wrap-smart-map env %)) x)

    :else
    x))

(defn sm-get
  "Get a property from a smart map.

  First it checks if the property is available in the cache-tree, if not it triggers
  the connect engine to lookup for the property. After the lookup is triggered the
  cache-tree will be updated in place, note this has a mutable effect in this data,
  but this change is consistent.

  Repeated lookups will use the cache-tree and should be as fast as reading from a
  regular Clojure map."
  ([env k] (sm-get env k nil))
  ([{::p.ent/keys [cache-tree*] :as env} k default-value]
   (let [cache-tree @cache-tree*]
     (if-let [x (find cache-tree k)]
       (wrap-smart-map env (val x))
       (let [ast   {:type     :root
                    :children [{:type :prop, :dispatch-key k, :key k}]}
             graph (pcp/compute-run-graph
                     (assoc env
                       ::pcp/available-data (pfsd/data->shape-descriptor cache-tree)
                       :edn-query-language.ast/node ast))]
         (pcr/run-graph! (assoc env ::pcp/graph graph))
         (wrap-smart-map env (get @cache-tree* k default-value)))))))

(defn sm-assoc
  "Creates a new smart map by adding k v to the initial context.

  When you read information in the smart map, that information is cached into an internal
  atom, and any dependencies that were required to get to the requested data is also
  included in this atom.

  When you assoc into a smart map, it will discard all the data loaded by Pathom itself
  and will be like you have assoced in the original map and created a new smart object."
  [env k v]
  (smart-map env
    (-> (::source-context env)
        (assoc k v))))

(defn sm-dissoc
  "Creates a new smart map by adding k v to the initial context.

  When you read information in the smart map, that information is cached into an internal
  atom, and any dependencies that were required to get to the requested data is also
  included in this atom.

  When you assoc into a smart map, it will discard all the data loaded by Pathom itself
  and will be like you have assoced in the original map and created a new smart object."
  [env k]
  (smart-map env
    (-> (::source-context env)
        (dissoc k))))

(defn sm-keys
  "Retrieve the keys in the smart map cache-tree."
  [env]
  (keys (p.ent/cache-tree env)))

(defn sm-contains?
  "Check if a property is present in the cache-tree."
  [env k]
  (contains? (p.ent/cache-tree env) k))

(defn sm-meta
  "Returns meta data of smart map, which is the same as the meta data from context
   map used to create the smart map."
  [env]
  (meta (p.ent/cache-tree env)))

(defn sm-with-meta
  "Return a new smart-map with the given meta."
  [env meta]
  (smart-map env (with-meta (p.ent/cache-tree env) meta)))

(defn sm-find
  "Check if attribute can be found in the smart map."
  [env k]
  (if (pci/attribute-available? env k)
    (misc/make-map-entry k (sm-get env k))))

; region type definition
#?(:clj
   (def-map-type SmartMap [env]
     (get [_ k default-value] (sm-get env k default-value))
     (assoc [_ k v] (sm-assoc env k v))
     (dissoc [_ k] (sm-dissoc env k))
     (keys [_] (sm-keys env))
     (meta [_] (sm-meta env))
     (with-meta [_ new-meta] (sm-with-meta env new-meta))
     (entryAt [_ k] (sm-find env k)))

   :cljs
   (deftype SmartMap [env]
     Object
     (toString [_] (pr-str* (p.ent/cache-tree env)))
     (equiv [_ other] (-equiv (p.ent/cache-tree env) other))

     ;; EXPERIMENTAL: subject to change
     (keys [_] (es6-iterator (keys (p.ent/cache-tree env))))
     (entries [_] (es6-entries-iterator (seq (p.ent/cache-tree env))))
     (values [_] (es6-iterator (vals (p.ent/cache-tree env))))
     (has [_ k] (sm-contains? env k))
     (get [_ k not-found] (-lookup (p.ent/cache-tree env) k not-found))
     (forEach [_ f] (doseq [[k v] (p.ent/cache-tree env)] (f v k)))

     ICloneable
     (-clone [_] (smart-map env (p.ent/cache-tree env)))

     IWithMeta
     (-with-meta [_ new-meta] (sm-with-meta env new-meta))

     IMeta
     (-meta [_] (sm-meta env))

     ICollection
     (-conj [coll entry]
            (if (vector? entry)
              (-assoc coll (-nth entry 0) (-nth entry 1))
              (loop [ret coll
                     es  (seq entry)]
                (if (nil? es)
                  ret
                  (let [e (first es)]
                    (if (vector? e)
                      (recur (-assoc ret (-nth e 0) (-nth e 1))
                        (next es))
                      (throw (js/Error. "conj on a map takes map entries or seqables of map entries"))))))))

     IEmptyableCollection
     (-empty [_] (-with-meta (smart-map env {}) meta))

     IEquiv
     (-equiv [_ other] (-equiv (p.ent/cache-tree env) other))

     IHash
     (-hash [_] (hash (p.ent/cache-tree env)))

     ISeqable
     (-seq [_] (-seq (p.ent/cache-tree env)))

     ICounted
     (-count [_] (count (p.ent/cache-tree env)))

     ILookup
     (-lookup [_ k] (sm-get env k nil))
     (-lookup [_ k not-found] (sm-get env k not-found))

     IAssociative
     (-assoc [_ k v] (sm-assoc env k v))
     (-contains-key? [_ k] (sm-contains? env k))

     IFind
     (-find [_ k] (sm-find env k))

     IMap
     (-dissoc [_ k] (sm-dissoc env k))

     IKVReduce
     (-kv-reduce [_ f init]
                 (reduce-kv (fn [cur k v] (f cur k (wrap-smart-map env v))) init (p.ent/cache-tree env)))

     IReduce
     (-reduce [coll f] (iter-reduce coll f))
     (-reduce [coll f start] (iter-reduce coll f start))

     IFn
     (-invoke [this k] (-lookup this k))
     (-invoke [this k not-found] (-lookup this k not-found))))
; endregion

(>def ::smart-map #(instance? SmartMap %))

(>defn sm-env
  "Extract the env map from the smart map."
  [^SmartMap smart-map]
  [::smart-map => map?]
  (.-env smart-map))

(defn sm-assoc!
  "Assoc on the smart map in place, this function mutates the current cache and return
  the same instance of smart map.

  You should use this only in cases where the optimization is required, try starting
  with the immutable versions first, given this has side effects and so more error phone."
  [^SmartMap smart-map k v]
  (swap! (-> smart-map sm-env ::p.ent/cache-tree*) assoc k v)
  smart-map)

(defn sm-dissoc!
  "Dissoc on the smart map in place, this function mutates the current cache and return
  the same instance of smart map.

  You should use this only in cases where the optimization is required, try starting
  with the immutable versions first, given this has side effects and so more error phone."
  [^SmartMap smart-map k]
  (swap! (-> smart-map sm-env ::p.ent/cache-tree*) dissoc k)
  smart-map)

(>defn ^SmartMap smart-map
  "Create a new smart map.

  Smart maps are a special data structure that realizes properties using Pathom resolvers.

  They work like maps and can be used interchangeable with it.

  To create a smart map you need send an environment with the indexes and a context
  map with the initial context data:

      (smart-map (pci/register [resolver1 resolver2]) {:some \"context\"})

  When the value of a property of the smart map is a map, that map will also be cast
  into a smart map, including maps inside collections."
  [env context]
  [(s/keys :req [:com.wsscode.pathom3.connect.indexes/index-oir])
   map? => ::smart-map]
  (->SmartMap
    (-> env
        (p.ent/with-cache-tree context)
        (assoc ::source-context context))))
