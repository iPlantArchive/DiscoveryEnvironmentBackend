(ns facepalm.c200-2014052701
  (:use [korma.core])
  (:require [cheshire.core :as cheshire])
  (:import [java.util UUID]))

(declare insert-tree-value)

(def ^:private version
  "The destination database version."
  "2.0.0:20140527.01")

(defn- insert-param-selection-value
  [{:keys [argument_value parameter_id]}]
  (let [arg (cheshire/decode argument_value true)]
    (insert :parameter_values
            (values {:parameter_id parameter_id
                     :is_default (or (:isDefault arg) false)
                     :name (:name arg)
                     :value (:value arg)
                     :description (:description arg)
                     :label (:display arg)}))))

(defn- insert-tree-items
  [parameter_id item_id items]
  (dorun (map #(insert-tree-value parameter_id item_id %1 %2)
              items
              (range))))

(defn- insert-tree-value
  [parameter_id parent_id item display_order]
  (let [item_id (UUID/randomUUID)]
    (insert :parameter_values
            (values {:id item_id
                     :parameter_id parameter_id
                     :parent_id parent_id
                     :is_default (or (:isDefault item) false)
                     :display_order display_order
                     :name (:name item)
                     :value (:value item)
                     :description (:description item)
                     :label (:display item)}))
    (insert-tree-items parameter_id item_id (:arguments item))
    (insert-tree-items parameter_id item_id (:groups item))))

(defn- insert-tree-selection-values
  [{:keys [argument_value parameter_id]}]
  (let [arg (cheshire/decode argument_value true)
        root_id (UUID/randomUUID)]
    (insert :parameter_values
            (values {:id root_id
                     :parameter_id parameter_id
                     :is_default (or (:isSingleSelect arg) false)
                     :name (:selectionCascade arg)}))
    (insert-tree-items parameter_id root_id (:arguments arg))
    (insert-tree-items parameter_id root_id (:groups arg))))

(defn- convert-selection-values
  []
  (println "\t* migrating Selection type validation_rule_arguments to parameter_values (this may take a minute or 2)...")
  (let [list-args (select [:validation_rule_arguments :vra]
                          (fields :vra.argument_value [:p.id :parameter_id])
                          (join [:validation_rules :r]
                                {:r.id :vra.rule_id})
                          (join [:parameters :p]
                                {:p.id :r.parameter_id})
                          (join [:parameter_types :pt]
                                {:pt.id :p.parameter_type})
                          (where {:argument_value [like "{%"]
                                  :pt.name [in ["TextSelection"
                                                "DoubleSelection"
                                                "IntegerSelection"]]}))]
    (dorun (map insert-param-selection-value list-args))))

(defn- convert-tree-selection-values
  []
  (println "\t* migrating TreeSelection type validation_rule_arguments to parameter_values...")
  (let [list-args (select [:validation_rule_arguments :vra]
                          (fields :vra.argument_value [:p.id :parameter_id])
                          (join [:validation_rules :r]
                                {:r.id :vra.rule_id})
                          (join [:parameters :p]
                                {:p.id :r.parameter_id})
                          (join [:parameter_types :pt]
                                {:pt.id :p.parameter_type})
                          (where {:argument_value [like "{%"]
                                  :pt.name "TreeSelection"}))]
    (dorun (map insert-tree-selection-values list-args))))

(defn- convert-defalut-values
  []
  (println "\t* migrating parameter defalut values to parameter_values...")
  (exec-raw "INSERT INTO parameter_values (parameter_id, value, is_default)
            (SELECT p.id AS parameter_id, defalut_value_v187 AS value, TRUE AS is_default
             FROM parameters p
             LEFT JOIN parameter_types pt ON pt.id = p.parameter_type
             WHERE CHAR_LENGTH(defalut_value_v187) > 0 AND pt.name NOT LIKE '%Selection')")
  (exec-raw "INSERT INTO parameter_values (parameter_id, value, is_default)
            (SELECT p.id AS parameter_id, f.name_v187 AS value, TRUE AS is_default
             FROM task_param_listing tp
             LEFT JOIN parameter_values pv ON tp.id = pv.parameter_id
             LEFT JOIN parameters p ON p.id = tp.id
             LEFT JOIN file_parameters f ON f.id = p.file_parameter_id
             WHERE value_type = 'Output' AND CHAR_LENGTH(f.name_v187) > 0 AND pv.value IS NULL)"))

(defn convert
  "Performs the database conversion."
  []
  (println "Performing the conversion for" version)
  (convert-defalut-values)
  (convert-selection-values)
  (convert-tree-selection-values))
