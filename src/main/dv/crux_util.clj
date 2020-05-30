(ns dv.crux-util
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn >def | => ?]]
    [crux.api :as crux]
    [crux.backup :as backup]
    [taoensso.timbre :as log])
  (:import [java.util Date]
           [java.util UUID]
           [crux.api ICruxDatasource ICruxAPI]))

(defn id? [id]
  (or (keyword? id) (uuid? id)))

;; I'm thinking you'd (binding [*crux-node*] in the web request layer
;; in middleware or interceptor then it should be available without needing
;; to wire it through the call stack
(def ^:dynamic *crux-node*)

(defn crux-node? [n] (instance? ICruxAPI n))
(comment (crux-node? *crux-node*))

;; show all attributes in the node
(comment (crux/attribute-stats crux-node))

(comment
  (backup/backup {:backup-dir "crux-backup"} crux-node)
  (backup/restore {:db-dir "restore-crux-store/db" :event-log-dir "restore-crux-store/event-log" :backup-dir "crux-backup"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Writing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn put-async
  ([data] (put-async *crux-node* data))
  ([crux-node data]
   (let [data (dissoc data :db/created-at :db/updated-at)]
     (log/info "Transacting data: " data)
     (crux/submit-tx crux-node [[:crux.tx/put data]]))))

(defn put
  ([data] (put *crux-node* data))
  ([crux-node doc]
   (crux/await-tx crux-node (put-async crux-node doc))))

(s/def ::crux-doc (s/keys :req [:crux.db/id]))
(s/def ::crux-tx-return (s/keys :req [:crux.tx/tx-id :crux.tx/tx-time]))
(s/def ::vec-of-docs (s/coll-of ::crux-doc :type vector?))

(>defn put-all-async
  "Uses crux put transaction to add a vector of documents to a specified node."
  [crux-node docs]
  [crux-node? ::vec-of-docs => ::crux-tx-return]
  (crux/submit-tx crux-node
    (mapv #(vector :crux.tx/put (dissoc % :db/created-at :db/updated-at)) docs)))

(comment
  (put-all-async *crux-node* [{:crux.db/id :first-test :name :hi}]))

(>defn put-all
  ([docs]
   [::vec-of-docs => ::crux-tx-return]
   (put-all *crux-node* docs))
  ([crux-node docs]
   [crux-node? ::vec-of-docs => ::crux-tx-return]
   (crux/await-tx crux-node (put-all-async crux-node docs))))
(comment
  (put-all crux-node [{:crux.db/id :first-test :name :hi}]))

(defn assoc-crux-id
  "kw field and map of data with a property for that kw to use as the crux.db/id"
  [field m]
  (assoc m :crux.db/id (get m field)))

(defn insert-entity
  "uses id-kw - keyword to get an id val for this entity to add crux.db/id to insert"
  ([id-kw e]
   (insert-entity *crux-node* id-kw e))
  ([crux-node id-kw e]
   (put crux-node (assoc-crux-id id-kw e))))

(defn update-entity
  ([entity-id field f]
   (update-entity *crux-node* entity-id field f))
  ([crux-node entity-id field f]
   (let [ent (crux/entity (crux/db crux-node) entity-id)
         new-val (update ent field f)]
     (put crux-node new-val))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity reading + query
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn q
  ([query]
   (q *crux-node* query))
  ([crux-node query] (crux/q (crux/db crux-node) query)))

(defn field-kws->clauses
  "returns: [[e :field1 field1] [e :field2 field2]]"
  [fields]
  (mapv vec (partition 3 (interleave (repeat 'e) fields (mapv (comp symbol name) fields)))))
(comment (field-kws->clauses task-fields))

(defn crux-select
  "
  returns a vector of maps of the fields to their corresponding values.
  fields is a vector of keywords
  (crux-select [:task/id :task/name :task/description])
  =>
  makes a query find ['id 'name 'description] :where ['e :task/id 'id] etc

  "
  ([fields] (crux-select *crux-node* fields))
  ([crux-node fields]
   (let [clauses (field-kws->clauses fields)
         field-names (mapv (comp symbol name) fields)
         query {:find field-names :where clauses}
         tuples (q crux-node query)]
     (mapv
       (fn [t]
         (apply hash-map (interleave fields t)))
       tuples))))

(defn entity-id-for-prop
  ([v] (entity-id-for-prop (crux/db *crux-node*) v))
  ([db [attr value]]
   (assert (.isInstance ICruxDatasource db) "You must pass a db to `entity-for-prop`")
   (ffirst (crux/q db
             {:find  ['?e]
              :where [['?e attr value]]
              :args  [{'attr attr 'value value}]}))))

(comment
  (entity-id-for-prop (crux/db crux-node) [:val "1"]))

(defn entity-with-prop
  "Get an entity that has a property with value. copied from crux site
  (entity-with-prop [:email \"myaddress@addres.com\"])"
  ([eid] (entity-with-prop *crux-node* eid))
  ([crux-node eid]
   (when eid
     (let [db (crux/db crux-node)]
       (if (vector? eid)
         (let [[attr value] eid]
           (recur crux-node (entity-id-for-prop db [attr value])))
         (crux/entity db eid))))))

(defn entity-id-with-prop
  [eid] (some-> (entity-with-prop eid) :crux.db/id))

(comment
  (crux.api/entity (crux/db *crux-node*) "c28ca34d-bd4c-4036-8db9-8b1bc7ed2c7b")
  (put {:crux.db/id :test1 :val "1"})
  (entity-with-prop crux-node [:val "1"])
  (entity-id-for-prop (crux/db crux-node) [:val "1"])
  (crux/entity (crux/db crux-node) :test1)
  (keys (crux/db crux-node))
  )

(>defn all-entities-exist?
  "Takes collection of prop to value: [[:task/id :id]]
  checks that they return an entity and not nil"
  [coll]
  [(s/coll-of (s/or :id id? :ident ::ident)) => boolean?]
  (every? entity-id-with-prop coll))

(defn entity
  ([entity-id] (entity *crux-node* entity-id))
  ([crux-node entity-id]
   (crux/entity (crux/db crux-node) entity-id)))

(s/def ::tx-timestamps (? (s/map-of #{:db/updated-at :db/created-at} (? inst?))))


(defn get-doc-created-at [crux-node id]
  (with-open [history (crux/open-entity-history (crux/db crux-node) id
                        :asc {:with-docs? false})]
    (-> (iterator-seq history) first :crux.tx/tx-time)))

(defn get-doc-updated-at [crux-node id]
  (with-open [history (crux/open-entity-history (crux/db crux-node) id
                        :desc {:with-docs? false})]
    (-> (iterator-seq history) first :crux.tx/tx-time)))

(comment (get-doc-created-at crux-node #uuid "b64302cc-33f9-44d7-a76b-b6405934ca57")
  (get-doc-updated-at crux-node #uuid "b64302cc-33f9-44d7-a76b-b6405934ca57")
  (crux/entity-history (crux/db crux-node) #uuid "b64302cc-33f9-44d7-a76b-b6405934ca57"
    :desc))

(>defn get-timestamps
  "use history api to get
  last update time and creation time for an entity.
  for an entity: nilable: {:db/updated-at last tx-time :db/created-at first tx-time}"
  ([id]
   [u/id? => ::tx-timestamps]
   (get-timestamps *crux-node* id))
  ([crux-node id]
   [crux-node? u/id? => ::tx-timestamps]
   (let [created-at (get-doc-created-at crux-node id)
         updated-at (get-doc-updated-at crux-node id)]
     (when (and updated-at created-at)
       {:db/updated-at updated-at :db/created-at created-at}))))

(comment
  (crux-select crux-node [:user/id])
  (get-timestamps #uuid"011391e2-1c39-4f71-8175-3fe098289d41")
  (get-timestamps #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10")
  (get-timestamps crux-node #uuid "e0fdda94-5cfe-4062-bf2a-1cdb2521e4f9"))

(defn history
  ([id] (history *crux-node* id :desc))
  ([id sort-order] (history *crux-node* id sort-order))
  ([crux-node id sort-order]
   (crux/entity-history (crux/db crux-node) id sort-order
     {:with-docs? true}))
  ([crux-node id sort-order opts]
   (crux/entity-history (crux/db crux-node) id sort-order opts)))

(comment
  (crux-select crux-node [:user/id])
  (history #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10")
  (history crux-node #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10" :asc {:with-docs? false})
  )

(defn domain-entity
  ([id] (domain-entity *crux-node* id))
  ([crux-node id]
   (merge (entity crux-node id)
     (get-timestamps crux-node id))))

(comment
  (domain-entity #uuid "e0fdda94-5cfe-4062-bf2a-1cdb2521e4f9"))

;; Pathom helpers

(defn join-ref
  "Takes field and either one id or a collection of ids and returns
  one hash-map for one id, or collection of hash-maps for coll of ids."
  [kw v]
  (if (coll? v)
    (mapv #(hash-map kw %) v)
    {kw v}))

(comment
  (join-ref :task/id [#uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"])
  (join-ref :task/id #{#uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"})
  (join-ref :task/id #uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"))

(>defn pathom-expand-entity
  "Returns an entity from crux with modifications.
  Fields is vec of tuples kw, updates from plain ids as come out of db into maps
  with the field name as key and val as the id itself, as required for pathom
  to process a join."
  [field-tuples id]
  [(s/coll-of (s/tuple qualified-keyword? qualified-keyword?) :type vector?) u/id? => map?]
  (let [ent (domain-entity id)]
    (reduce
      (fn [ent [prop id-kw]]
        (update ent prop #(join-ref id-kw %)))
      ent
      field-tuples)))

(comment
  (pathom-expand-entity
    [[:user/tasks :task/id]
     [:user/habits :habit/id]]
    #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10")
  )

(defn entity-at
  [entity-id valid-time]
  (crux/entity (crux/db *crux-node* valid-time) entity-id))

(>defn read-merge-entity
  "Update an existing entity using the given map, deals with fetching the entity first. does not write"
  ([id-attr new-attrs]
   [keyword? map? => map?]
   (read-merge-entity *crux-node* id-attr new-attrs))

  ([crux-node id-attr new-attrs]
   [some? keyword? map? => map?]
   (let [entity-id (id-attr new-attrs)
         entity-prev-value (entity crux-node entity-id)]
     (merge entity-prev-value new-attrs {:crux.db/id entity-id}))))

(comment
  (read-merge-entity :user/id {:user/id #uuid "2eb987f0-d580-4545-8b26-671ac8083260"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Update entity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn merge-entity
  "Assumes entity exists are you're creating a new one (you need to pass crux.db/id if so
  or use merge-domain-entity).
  Update an existing entity using the given map, deals with fetching the entity first."
  ([entity-id new-attrs]
   {:pre [(or (vector? entity-id) (id? entity-id)) (map? new-attrs)]}
   (merge-entity *crux-node* entity-id new-attrs (Date.)))

  ([crux-node entity-id new-attrs]
   {:pre [(or (vector? entity-id) (id? entity-id)) (map? new-attrs)]}
   (merge-entity crux-node entity-id new-attrs (Date.)))

  ([crux-node entity-id new-attrs valid-time]
   {:pre [(or (vector? entity-id) (id? entity-id)) (map? new-attrs)]}
   (let [entity-prev-value (entity-with-prop crux-node entity-id)
         _ (log/info "merge prev valu: " entity-prev-value)
         new-val (merge entity-prev-value new-attrs)]
     (log/info "merge entity new val: " new-val)
     (crux/submit-tx crux-node [[:crux.tx/put new-val valid-time]]))))

(comment
  (merge-entity (:task/id %) (assoc %)))

(defn merge-domain-entity
  "field where unique key is and new data `m`"
  [id-kw m]
  (let [id (id-kw m)]
    (if id
      (merge-entity id (assoc-crux-id id-kw m))
      (throw (Exception. (str "Missing id field: " id-kw " in map: " m))))))

(defn ensure-key [k]
  (if (id? k) k (UUID/fromString k)))

(defn delete
  "Key is either [:some-prop \"value\"]
  or a value for crux.db/id"
  ([key] (delete *crux-node* key))
  ([crux-node key]
   (let [key (:crux.db/id (entity-with-prop key))]
     (log/info "Deleting entity with key: " (pr-str key))
     (crux/await-tx
       crux-node
       (crux/submit-tx crux-node [[:crux.tx/delete (ensure-key key)]])))))

(defn delete-entities-with-field
  "For all entities with the field `field` deletes them."
  [field]
  (dorun (map
           #(delete (field %))
           (crux-select *crux-node* [field]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data migration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; 1. query for all documents with old property
; 2. Write new document

(defn migrate-attr
  "Finds all documents with `old-attr`, invokes `xf` on the value of old-attr and
  assoces it at `new-attr`"

  [old-attr new-attr xf]
  (->> (crux-select [:crux.db/id old-attr])
    (into []
      (map (comp
             #(dissoc % old-attr)
             ;; todo just call xf with the whole doc as an alternative
             #(assoc % new-attr (xf (old-attr %)))
             entity
             :crux.db/id)))
    put-all))

(comment
  (migrate-attr :habit/start-at :habit/starts-on tu/->date))