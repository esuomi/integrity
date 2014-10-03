(ns integrity.datomic
  "Maps datomic attribute definitions to prismatic schemata
and vice versa"
  (:require [datomic.api :as d]
            [schema.core :as s :refer [Str Num Inst Int Bool Keyword]]
            [clojure.walk :as w]))

(def Ref {:schema (s/either Int Keyword)
          :attr-factory (fn [ident]
                          {:db/id (d/tempid :db.part/db)
                           :db/ident ident
                           :db/valueType :db.type/ref
                           :db/cardinality :db.cardinality/one
                           :db.install/_attribute :db.part/db})})

(def ^{:private true}
  schema->datomic
  {Str                      :db.type/string
   Bool                     :db.type/boolean
   Long                     :db.type/long
   ;java.Math.BigInteger     :db.type/bigint
   Num                      :db.type/double
   Int                      :db.type/long
   Float                    :db.type/float
   Inst                     :db.type/instant
   ;java.Math.BigDecimal     :db.type/bigdec
   })

(defmulti attribute
  "Implementing methods should return a function that will generate a
datomic attribute when given it's id as the one and only argument"
  (fn dispatch
    ([ident schema]
       (dispatch ident schema false))
    ([ident schema unique]
       (class schema))))

(defmethod attribute ::leaf
  ([ident schema]
     (attribute ident schema false))
  ([ident schema unique]
     (let [uniqueness (if unique {:db/unique unique} {})]
       (merge uniqueness
              {:db/id (d/tempid :db.part/db)
               :db/ident ident
               :db/valueType ((comp val find) schema->datomic schema)
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}))))

(defmethod attribute ::vector
  ([ident schema]
     (attribute ident schema false))
  ([ident schema unique]
     (if unique
       (throw (Exception. "attributes with cardinality of many cannot be unique"))
       {:db/id (d/tempid :db.part/db)
        :db/ident ident
        :db/valueType ((comp val find) schema->datomic (first schema))
        :db/cardinality :db.cardinality/many
        :db.install/_attribute :db.part/db})))

(defn attributes
  "Recursively generates facts for each key of the input schema

Schema should be a map. Its keys should be attribute names and
its values should be either Schema \"leaf\" values like Str, Bool,
Num etc, or another schema. In addition to generating datomic
attributes for the nested schema, we also generate a datomic
reference attribute that allows the structures to be linked in
datomic. Documentation on writing schemas can be found at

https://github.com/Prismatic/schema

If uniqueness is specified, it should be a hash where each key means
the attribute with that name is marked as unique in datomic. The
value can be used to determine the type of uniqueness. For details
about the different types of uniqueness, refer to the datomic
documentation which can be found at

http://docs.datomic.com/identity.html
"
  ([schema]
     (attributes schema {}))
  ([schema uniqueness]
     (let [mk-attr (fn [k v]
                     (attribute k v (k uniqueness)))]
       (reduce (fn [acc [k v]]
                 (if (extends? schema.core/Schema (class v))
                   (into acc [(mk-attr k v)])
                   (into acc (conj (attributes (into {} v) uniqueness)
                                   ((:attr-factory Ref) k)))))
               []
               (seq schema)))))

(derive java.lang.Class                ::leaf)

;; This is only required because some single valued Schema types (e.g. Int)
;; are implemented as predicates. We're not trying to generate datomic attributes
;; for arbitrary predicates
(derive schema.core.Predicate          ::leaf)

(derive clojure.lang.IPersistentVector ::vector)
