(ns mycc.admin.core
  (:require
   [mycc.common.db :as db]
   [mycc.p2p.db :as p2p.db]))

;number of new students per month
;number of active students per month

(->> (db/get-users)
     (group-by (fn [user])))
