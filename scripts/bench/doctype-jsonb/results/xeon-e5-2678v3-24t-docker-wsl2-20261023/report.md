# DocType JSONB benchmark: xeon-e5-2678v3-24t-docker-wsl2-20261023

| Machine | |
| --- | --- |
| note | second run; before bench.sh existed (same SQL and runners; orchestrated by hand); PostgreSQL in Docker Desktop on Windows 10 (WSL2 VM 16 GB; 24 CPU; shared with ~3 GB of other containers); data on a VHDX on the NVMe; pgbench inside the PostgreSQL container. Original chain with the first-run settings (max_wal_size 4GB); limits phase with the tuned settings below |
| product | (desktop) |
| cpu_model | Intel(R) Xeon(R) CPU E5-2678 v3 @ 2.50GHz |
| cpus | 24 |
| ram_mb | 32651 |
| data_device | WD Blue SN570 500GB NVMe (through a Docker Desktop VHDX) |
| postgres | – |
| profile | auto (limits phase) |
| rows | 5000000 |
| fdatasync_ops_per_s | – |

## Single queries, warm (ms)

| Query | JSONB baseline | JSONB fixed | Traditional |
| --- | --- | --- | --- |
| q1 | 0.122 | – | 0.225 |
| q2 | 866.979 | 0.286 | 0.194 |
| q3b | 9.575 | 0.415 | 5.920 |
| q4 | 864.391 | 0.286 | 0.184 |
| q5 | 27.527 | 103.609 | 22.674 |
| q6 | 766.322 | 2.166 | 229.774 |
| q7 | 1181.901 | 787.667 | 655.925 |
| q9 | 876.393 | 0.290 | 0.121 |
| q10 | 247.539 | 0.314 | 247.754 |

## Read throughput by connections (transactions/s, p99 ms of the point get)

| Clients | JSONB tps | JSONB p99 | Traditional tps | Traditional p99 |
| --- | --- | --- | --- | --- |
| 8 | 11980 | 0.97 | 10593 | 1.31 |
| 16 | 21186 | 1.25 | 22648 | 1.11 |
| 32 | 28964 | 2.18 | 30242 | 2.27 |
| 64 | 32740 | 7.01 | 34467 | 6.34 |
| 128 | 28828 | 12.12 | 30021 | 11.03 |
| 256 | 25817 | 22.46 | 28119 | 18.85 |

## Writes, kernel-shaped transactions (transactions/s at 4 / 16 / 64 clients)

| Workload | c=4 | c=16 | c=64 | p99 ms at 64 |
| --- | --- | --- | --- | --- |
| insert-series-jsonb | 374 | 291 | 425 | 758.03 |
| insert-series-rel | 449 | 409 | 584 | 635.99 |
| insert-seq-jsonb | 1370 | 5118 | 8868 | 36.12 |
| insert-seq-rel | 1561 | 4840 | 10628 | 34.51 |
| update-order-jsonb | 1288 | 3741 | 8246 | 28.02 |
| update-waybill-jsonb | 1180 | 3846 | 7050 | 45.93 |
| update-waybill-2tx-jsonb | 1129 | 3587 | 6493 | 39.63 |
| update-rel | 1211 | 4206 | 7655 | 44.60 |

## Peak-hour mix (1 create : 8 scans : 14 gets : 6 customer lists : 2 status lists)

| Run | tps | create p99 | scan p99 | get p99 | CPU % |
| --- | --- | --- | --- | --- | --- |
| mix-jsonb-c16 | 6710 | 70.50 | 15.90 | 2.18 | 31 |
| mix-jsonb-c32 | 8452 | 407.64 | 16.24 | 1.80 | 42 |
| mix-jsonb-c64 | 7145 | 1936.56 | 22.13 | 2.14 | 39 |
| mix-jsonb-c128 | 7756 | 2603.06 | 20.36 | 3.37 | 50 |
| mix-jsonb-seq-c64 | 14522 | 54.06 | 58.98 | 4.19 | 62 |
| mix-rel-c64 | 8357 | 1474.32 | 16.84 | 2.31 | 43 |
| rate-jsonb-R7200 | 7202 | 315.98 | 139.77 | 118.58 | 31 |
| rate-jsonb-R14400 | 6950 | 31172.60 | 30908.16 | 30902.51 | 40 |
| rate-jsonb-seq-R7200 | 7118 | 5998.41 | 6017.49 | 5984.86 | 36 |
| rate-jsonb-seq-R10800 | 10317 | 3904.65 | 3942.55 | 3929.51 | 46 |
| rate-jsonb-seq-R14400 | 11543 | 11769.82 | 11779.63 | 11769.15 | 54 |
| rate-rel-seq-R7200 | 7070 | 9143.71 | 9111.66 | 9071.71 | 34 |
| rate-rel-seq-R10800 | 10810 | 1769.28 | 1776.22 | 1760.23 | 45 |
| rate-rel-seq-R14400 | 14397 | 3122.57 | 3123.12 | 3115.18 | 58 |
| mix-hot-jsonb-seq-c64 | – | – | – | – | – |
| syncoff-mix-jsonb-seq-c64 | 16555 | 26.39 | 29.36 | 6.33 | 67 |
| gin-mix-jsonb-seq-c64 | 10406 | 38.20 | 39.60 | 6.52 | 56 |

## Trade-offs

| Run | tps | p99 ms |
| --- | --- | --- |
| get-uniform-jsonb-c64 | 36637 | 5.37 |
| get-hot-jsonb-c64 | 36747 | 7.38 |
| insert-seq-jsonb-c16 | 5118 | 10.21 |
| syncoff-insert-seq-jsonb-c16 | 8321 | 4.57 |
| update-waybill-jsonb-c16 | 3846 | 17.44 |
| syncoff-update-waybill-jsonb-c16 | 7174 | 7.10 |
| insert-seq-jsonb-c64 | 8868 | 36.12 |
| gin-insert-seq-jsonb-c64 | 8533 | 18.21 |
| update-waybill-jsonb-c64 | 7050 | 45.93 |
| gin-update-waybill-jsonb-c64 | 6377 | 27.71 |

Raw outputs: every file next to this report. Key/value form: metrics.csv.
