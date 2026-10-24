# DocType JSONB benchmark: xeon-e5-2678v3-24t-docker-wsl2-20261023-partition

| Machine | |
| --- | --- |
| note | partition + materialised range; same machine as the 20261023 run |
| product |  |
| cpu_model | Intel(R) Xeon(R) CPU E5-2678 v3 @ 2.50GHz |
| cpus | 24 |
| ram_mb | 15943 |
| data_device |  |
| postgres | – |
| profile | auto |
| rows | 5000000 |
| fdatasync_ops_per_s | 771.822 |

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
| get | 34269 | 13818 | 6.43 / 11.73 |
| list-customer | 32911 | 8650 | 7.57 / 23.98 |
| list-status | 25132 | 7847 | 9.95 / 26.60 |
| insert | 6399 | 8793 | 16.75 / 31.33 |
| update-waybill | 6912 | 3232 | 35.26 / 36.33 |
| mix | 13517 | 6337 | 48.30 / 53.30 |
| COD range, column, 16 clients | 66 | 38 | 280.40 / 552.22 |
| COD range, from JSON, 16 clients | – | 18 | – / 1244.91 |

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
