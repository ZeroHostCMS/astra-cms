# DocType JSONB benchmark: m1pro-10c-16g-docker-20261022

| Machine | |
| --- | --- |
| note | first run; reconstructed from docs/why_doctype_jsonb.md; PostgreSQL in Docker Desktop (16 GB; 10 CPU); pgbench 18.3 on the macOS host; stopped when the disk filled during the bigint rewrite |
| product | MacBook Pro |
| cpu_model | Apple M1 Pro |
| cpus | 10 |
| ram_mb | 16384 |
| data_device | Docker Desktop VM disk on the internal SSD |
| postgres | 16.15 (pgvector/pgvector:pg16 in Docker) |
| profile | fixed16 |
| rows | 5000000 |
| fdatasync_ops_per_s | – |

## Single queries, warm (ms)

| Query | JSONB baseline | JSONB fixed | Traditional |
| --- | --- | --- | --- |
| q1 | 0.071 | – | 0.075 |
| q2 | 470.244 | 0.130 | 0.065 |
| q3b | 5.007 | 0.220 | 2.938 |
| q4 | 608.209 | 0.129 | 0.078 |
| q5 | 25.387 | 25.613 | 11.762 |
| q6 | 534.494 | 0.946 | 135.941 |
| q7 | 862.612 | 6410.674 | 353.384 |
| q9 | 545.166 | 0.145 | 0.085 |
| q10 | 199.907 | 0.241 | 217.795 |

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
