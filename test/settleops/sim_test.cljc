(ns settleops.sim-test
  "A smoke test for the offline demo (`clojure -M:dev:run`). It asserts
  what the demo CLAIMS: that it runs with no network and no keys, that an
  unverified payout destination stops a basket, and that the escrow only
  reaches `:released` after a named human approves.

  Deliberately a smoke test and nothing more -- the behaviours are pinned
  properly in `governor-test` / `operation-graph-test`; what this catches
  is the demo itself rotting (a renamed key, a graph that no longer
  compiles) while the unit tests stay green."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [settleops.sim :as sim]))

(deftest the-offline-demo-runs-and-tells-the-truth
  (let [out (with-out-str (sim/-main))]
    (testing "all four scenarios ran"
      (is (str/includes? out "1. 複数出品者バスケットの精算計算"))
      (is (str/includes? out "2. 未検証の支払先を含むバスケット"))
      (is (str/includes? out "3. エスクロー解放は必ず人間の承認を通る"))
      (is (str/includes? out "4. 未配達の注文は人間にすら聞かずに拒否")))
    (testing "conservation held and nothing was custodial"
      (is (str/includes? out "保存則      : true"))
      (is (str/includes? out "預託しない: true")))
    (testing "the release waited for a human and then carried their name"
      (is (str/includes? out "（承認前）"))
      (is (str/includes? out "人間 treasury-01 が承認"))
      (is (re-find #":released by treasury-01" out)))
    (testing "the audit ledger was written"
      (is (str/includes? out "=== 監査台帳 ===")))
    (testing "and no transfer was ever executed from this repository"
      (is (not (re-find #"(?i)executed\?\s*:?\s*true" out))))))
