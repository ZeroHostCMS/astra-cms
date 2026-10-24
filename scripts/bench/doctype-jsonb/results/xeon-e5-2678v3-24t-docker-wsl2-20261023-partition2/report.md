# DocType JSONB benchmark: xeon-e5-2678v3-24t-docker-wsl2-20261023-partition2

| Machine | |
| --- | --- |
| note | partition with name-date pruning; range filters planned per value; same machine |
| product |  |
| cpu_model | Intel(R) Xeon(R) CPU E5-2678 v3 @ 2.50GHz |
| cpus | 24 |
| ram_mb | 15943 |
| data_device |  |
| postgres | – |
| profile | auto |
| rows | 5000000 |
| fdatasync_ops_per_s | 995.204 |

## Single queries, warm (ms)

| Query | JSONB baseline | JSONB fixed | Traditional |
| --- | --- | --- | --- |
| q1 | – | – | – |
| q2 | – | – | – |
| q3b | – | – | – |
| q4 | – | – | – |
| q5 | – | – | – |
| q6 | – | – | – |
| q7 | – | – | – |
| q9 | – | – | – |
| q10 | – | – | – |

## Read throughput by connections (transactions/s, p99 ms of the point get)

| Clients | JSONB tps | JSONB p99 | Traditional tps | Traditional p99 |
| --- | --- | --- | --- | --- |
| 8 | – | – | – | – |
| 16 | – | – | – | – |
| 32 | – | – | – | – |
| 64 | – | – | – | – |
| 128 | – | – | – | – |
| 256 | – | – | – | – |

## Writes, kernel-shaped transactions (transactions/s at 4 / 16 / 64 clients)

| Workload | c=4 | c=16 | c=64 | p99 ms at 64 |
| --- | --- | --- | --- | --- |
| insert-series-jsonb | – | – | – | – |
| insert-series-rel | – | – | – | – |
| insert-seq-jsonb | – | – | – | – |
| insert-seq-rel | – | – | – | – |
| update-order-jsonb | – | – | – | – |
| update-waybill-jsonb | – | – | – | – |
| update-waybill-2tx-jsonb | – | – | – | – |
| update-rel | – | – | – | – |

## Peak-hour mix (1 create : 8 scans : 14 gets : 6 customer lists : 2 status lists)

| Run | tps | create p99 | scan p99 | get p99 | CPU % |
| --- | --- | --- | --- | --- | --- |
| mix-jsonb-c16 | – | – | – | – | – |
| mix-jsonb-c32 | – | – | – | – | – |
| mix-jsonb-c64 | – | – | – | – | – |
| mix-jsonb-c128 | – | – | – | – | – |
| mix-jsonb-seq-c64 | – | – | – | – | – |
| mix-rel-c64 | – | – | – | – | – |
| rate-jsonb-R7200 | – | – | – | – | – |
| rate-jsonb-R14400 | – | – | – | – | – |
| rate-jsonb-seq-R7200 | – | – | – | – | – |
| rate-jsonb-seq-R10800 | – | – | – | – | – |
| rate-jsonb-seq-R14400 | – | – | – | – | – |
| rate-rel-seq-R7200 | – | – | – | – | – |
| rate-rel-seq-R10800 | – | – | – | – | – |
| rate-rel-seq-R14400 | – | – | – | – | – |
| mix-hot-jsonb-seq-c64 | – | – | – | – | – |
| syncoff-mix-jsonb-seq-c64 | – | – | – | – | – |
| gin-mix-jsonb-seq-c64 | – | – | – | – | – |

## Month partitioning (18 partitions) and materialised range filters, transactions/s

| Operation | astra_doc | astra_doc_txn (partitioned) | p99 ms doc / txn |
| --- | --- | --- | --- |
| get | 33787 | 13797 | 5.96 / 12.15 |
| list-customer | 33292 | 8747 | 7.66 / 24.78 |
| list-status | 25455 | 7952 | 9.72 / 26.70 |
| insert | 8826 | 9941 | 11.20 / 22.27 |
| update-waybill | 8416 | 3619 | 19.24 / 34.67 |
| mix | 14306 | 6451 | 42.98 / 36.01 |
| get, name-date bound | – | 17780 | – / 9.39 |
| update-waybill, name-date bound | – | 3999 | – / 38.66 |
| mix, name-date bound | – | 8294 | – / 33.62 |
| COD range, column, generic plan, 16 clients | 46 | 38 | 373.24 / 555.56 |
| COD range, column, plan per value, 16 clients | 135 | 97 | 335.77 / 440.13 |
| COD range, from JSON, 16 clients | – | 16 / 16 per value | – / 1349.05 |

## Trade-offs

| Run | tps | p99 ms |
| --- | --- | --- |
| get-uniform-jsonb-c64 | – | – |
| get-hot-jsonb-c64 | – | – |
| insert-seq-jsonb-c16 | – | – |
| syncoff-insert-seq-jsonb-c16 | – | – |
| update-waybill-jsonb-c16 | – | – |
| syncoff-update-waybill-jsonb-c16 | – | – |
| insert-seq-jsonb-c64 | – | – |
| gin-insert-seq-jsonb-c64 | – | – |
| update-waybill-jsonb-c64 | – | – |
| gin-update-waybill-jsonb-c64 | – | – |

Raw outputs: every file next to this report. Key/value form: metrics.csv.
