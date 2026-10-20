<div align="center">

# Astra CMS

**Next-Gen Cloud-Native Java Quarkus Headless CMS & Transactional Enterprise Engine**  
*Hybrid Build-time & Cluster-Synced Runtime Plugins • Native Apache Seata (2PC/TCC) HA • Modern Java 25+ on Kubernetes*

[![Java Version](https://img.shields.io/badge/Java-25%2B%20LTS%20(Gen%20ZGC)-orange.svg)](https://openjdk.org/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.x-blue.svg)](https://quarkus.io/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Cloud%20Native%20HA-326ce5.svg)](https://kubernetes.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%2B%20JSONB-blueviolet.svg)](https://www.postgresql.org/)
[![Redis Cluster](https://img.shields.io/badge/Redis%20Cluster-Hash--Tag%20TCC%20%26%20L2-dc382d.svg)](https://redis.io/)
[![Apache Seata](https://img.shields.io/badge/Apache%20Seata-2.x%20Raft%202PC%2FTCC-red.svg)](https://seata.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

---

## Overview

**Astra CMS** is an open-source, Kubernetes-native Headless CMS and High-Throughput Enterprise Application Engine built on **Java 25+ LTS** and **Quarkus 3.x**. It is engineered from the ground up to serve **millions of concurrent users** and mission-critical enterprise workloads by solving three fundamental bottlenecks in modern content and digital commerce platforms: **runtime throughput**, **distributed data consistency**, and **zero-downtime multi-tenant extensibility**.

Traditional headless CMS platforms (whether Node.js-based engines or legacy monolithic Java CMSs) break down when used as the transactional backbone for large-scale enterprise systems—suffering from data drift between databases and caches, state desynchronization across Kubernetes pods, sluggish startup times, or rigid plugin architectures.

**Astra CMS** eliminates these limitations through four architectural pillars:
1. **A Cluster-Synchronized Dual-Mode Plugin Architecture:** Combining **Build-time Extensions** (for raw native speed and deep CDI/DB wiring) with **Distributed Runtime Plugins** (for zero-downtime hot-swapping across Kubernetes pods via S3/MinIO artifact streaming and AOT-compiled WebAssembly sandboxes).
2. **Zero-Overhead Native Apache Seata (2PC / TCC) Integration:** Guaranteeing strict ACID consistency across **PostgreSQL** and **Redis Cluster** out of the box, while intelligently bypassing proxy overhead on non-transactional paths.
3. **Modern Java 25+ Concurrency Engine:** Leveraging Unpinned Virtual Threads (JEP 491), `ScopedValue` context propagation, `StructuredTaskScope`, and Generational ZGC for sub-millisecond pause times under massive load.
4. **Kubernetes-Native Multi-Pod Coherency:** Built-in distributed control plane for L1/L2 cache invalidation, dynamic schema synchronization, and Raft-backed transaction coordination.

---

## Cloud-Native Architecture Blueprint (Kubernetes HA)

```text
+-----------------------------------------------------------------------------------+
|                        KUBERNETES INGRESS / API GATEWAY                           |
|          REST (RESTEasy Reactive)     |     GraphQL     |     gRPC (Internal)     |
+-----------------------------------------------------------------------------------+
                                            |
+-----------------------------------------------------------------------------------+
|                ASTRA CORE ENGINE PODS (K8s HPA • JDK 25 Gen-ZGC)                  |
|  +-----------------------+  +-----------------------+  +-----------------------+  |
|  |    Content Engine     |  |   Clustered EventBus  |  |    Security & RBAC    |  |
|  | (Zero-DDL JSONB/GIN)  |  | (Vert.x + Redis Sync) |  |  (OIDC / Multi-Ten)   |  |
|  +-----------------------+  +-----------------------+  +-----------------------+  |
|  |       L1 Cache (Caffeine) <---> Redis Pub/Sub Cache Invalidation Bus        |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
                  |                                                |
+----------------------------------------+  +---------------------------------------+
|       ASTRA TRANSACTION MANAGER        |  |     HYBRID PLUGIN ENGINE (Dual-Mode)  |
|   Apache Seata 2.x Raft Cluster HA     |  |  1. Build-time (Jandex / Quarkus)     |
|   - Smart Bypass Agroal DataSourceProxy|  |  2. Cluster-Synced Runtime Hot-Swap:  |
|   - AT Mode: Postgres PK/JSONB Safe    |  |     - Leak-Free Isolated JAR Loader   |
|   - TCC Mode: Redis Cluster Hash-Tag   |  |     - Chicory Wasm AOT + Fuel Quota   |
|   - ScopedValueContextCore SPI Bridge  |  |     - S3/MinIO Control-Plane Pull     |
+----------------------------------------+  +---------------------------------------+
                  |                                                |
+-----------------------------------------------------------------------------------+
|                         HIGH-AVAILABILITY DATA & STORAGE                          |
|  PostgreSQL HA (JSONB + Partitioned undo_log) | Redis Cluster (L2 + TCC) | S3/MinIO|
+-----------------------------------------------------------------------------------+

```

---

## Core Pillars & Enterprise Features

### 1. Dual-Mode Hybrid Plugin System (Build-time + K8s Runtime Hot-Swap)

Enterprise platforms demand both uncompromising throughput for core infrastructure and instant agility for tenant-specific business logic. Astra CMS provides a unified `AstraPlugin` SPI operating across two complementary modes:

| Feature / Dimension | 🏗️ Mode A: Build-time Extensions | 🔥 Mode B: Cluster-Synced Runtime Plugins |
| --- | --- | --- |
| **Mechanism** | Quarkus Build-time Augmentation (`@BuildStep` & **Jandex** bytecode indexing). | **Isolated JVM ClassLoaders** (PF4J-style JARs) & **Chicory WebAssembly (Wasm)** sandboxes with **AOT Bytecode Compilation**. |
| **K8s Deployment** | Immutable container image built via `astra build` CLI (Keycloak / Caddy pattern). | **Control-Plane Pull & Broadcast:** Uploaded to **S3/MinIO** via Admin API; **Redis Pub/Sub** triggers simultaneous hot-swap across all K8s Pods. |
| **Capabilities** | Full CDI (`@Inject`), Hibernate Entities, Seata Bytecode Proxies, Custom DB Adapters. | Dynamic REST routes, Content Lifecycle Hooks, Webhooks, Payment/Shipping adapters, Tenant Workflow rules. |
| **Isolation & Safety** | Shared Core ClassLoader (Maximum speed, zero serialization overhead). | Auto-tracked lifecycle (Zero Metaspace ClassLoader leaks) or 100% crash-proof Wasm sandbox with strict **CPU Fuel & Memory Quotas**. |
| **Runtime Target** | **JVM Mode (JDK 25)** & **GraalVM Native (`-Pnative`)**. | **JAR + Wasm** on JDK 25 JVM; **Wasm AOT/Interpreter** on GraalVM Native (preserving Closed-World safety). |

---

### 2. Enterprise 2PC Distributed Transactions (Apache Seata 2.x HA)

When coordinating state across PostgreSQL and Redis Cluster (for flash-sale inventory, rate-limiting, digital wallets, or order workflows), standard `MULTI/EXEC` blocks or `afterCommit` hooks fail under node crashes or network partitions. Astra CMS integrates **Apache Seata 2.x** natively into the Quarkus execution model:

* **Zero-Overhead Smart Bypass `DataSourceProxy`:**
* Wraps Quarkus `AgroalDataSource` with an intelligent Seata proxy.
* **Zero Penalty on Read/Non-2PC Paths:** If the current `ScopedValue` context holds no active Global Transaction XID, queries bypass AST parsing and lock acquisition completely, executing directly on the raw `AgroalDataSource`.
* **PostgreSQL AT Mode (JSONB-Safe):** On `@AstraGlobalTransactional` paths, updates target indexed primary keys to generate compact `before-image` and `after-image` snapshots in partitioned PostgreSQL `undo_log` tables without triggering complex JSONB AST parser bottlenecks.


* **Redis Cluster TCC Engine (`AstraRedisTccTemplate`):**
* Built-in atomic, Lua-script-backed **Try-Confirm-Cancel (TCC)** engine built on `quarkus-redis-client`.
* **Multi-Shard Redis Cluster Ready:** Enforces **Redis Hash Tags (`{resource_key}`)** across all state keys (`{key}:available`, `{key}:frozen:{xid}`, `{key}:tx_state:{xid}`), guaranteeing single-slot atomic Lua execution without `CROSSSLOT` exceptions.
* **Full Anomaly Protection:** State machine guards stored with TTLs automatically prevent **Empty Rollback**, **Hanging (Try arriving after Cancel)**, and guarantee strict **Idempotency** during Phase-2 retries.


* **`ScopedValueContextCore` SPI Bridge:**
* Custom Seata `ContextCore` implementation bridging Seata's internal `RootContext` directly to Java 25 `ScopedValue`, enabling leak-free XID propagation across millions of Virtual Threads.



---

### 3. Modern Java 25+ Concurrency & Low-Latency Runtime

Astra CMS is engineered specifically for the **Java 25+ LTS** runtime with **Generational ZGC** enabled by default:

* **Unpinned Virtual Threads (JEP 491):** Every HTTP request, plugin hook, and Seata RPC/JDBC call executes on lightweight Virtual Threads without object monitor pinning—delivering reactive-grade concurrency with clean, debuggable imperative code.
* **Scoped Values (`ScopedValue`):** Replaces legacy `ThreadLocal` for propagating `Seata XID`, `TenantContext`, `TraceContext`, and `SecurityIdentity` safely and immutably across virtual threads and child tasks.
* **Structured Concurrency (`StructuredTaskScope`):** Executes multi-plugin hooks and Phase-1 resource reservations concurrently. Using `ShutdownOnFailure`, if any branch (PostgreSQL, Redis Cluster, or external gRPC) fails, sibling tasks are immediately cancelled and a global Seata rollback is initiated.

---

### 4. High-Throughput Hybrid Storage & Cluster-Coherent Caching

* **Zero-DDL Dynamic Schemas:** Creating or evolving a Content-Type never runs blocking `ALTER TABLE` or `CREATE TABLE` DDL locks in production. Core metadata (`id`, `tenant_id`, `type`, `status`, `version`, `created_at`) resides in B-Tree indexed relational columns, while dynamic attributes live in binary `JSONB` accelerated via tuned **GIN (`jsonb_path_ops`)** indexes (`fastupdate = on`, optimized `gin_pending_list_limit`, and dedicated autovacuum workers).
* **Multi-Pod Coherent L1/L2 Caching:**
* **L1 In-Memory Cache (Caffeine):** Ultra-fast local Pod caching for Content-Type schemas, RBAC policies, and compiled JSON schemas.
* **Cluster Cache Invalidation Bus:** Any schema, permission, or plugin mutation broadcasts an invalidation event over **Redis Pub/Sub / Clustered Vert.x EventBus**, evicting stale L1 entries across all Kubernetes pods within milliseconds.
* **L2 Distributed Cache (Redis Cluster):** Transaction-aware object cache synchronized with Seata commit/rollback hooks.



---

## 💻 Developer Experience (Code Examples)

### 1. Writing a Multi-Shard Transactional Plugin (2PC across Postgres & Redis Cluster)

Plugins depend solely on the zero-dependency `astra-spi` module:

```java
package io.astra.plugins.flashsale;

import io.astra.spi.content.ContentPayload;
import io.astra.spi.plugin.AstraPlugin;
import io.astra.spi.plugin.PluginContext;
import io.astra.spi.tx.AstraGlobalTransactional;
import io.astra.spi.tx.AstraTccAction;
import io.astra.spi.tx.AstraRedisTccTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class FlashSaleInventoryPlugin implements AstraPlugin {

    @Inject
    AstraRedisTccTemplate redisTcc;

    @Inject
    OrderContentService orderService; // Backed by PostgreSQL (Seata AT Mode)

    @Override
    public String getId() {
        return "io.astra.plugins.flash-sale";
    }

    @Override
    public void onStart(PluginContext context) {
        // Lifecycle-managed registration: automatically deregistered if plugin unloads
        context.hooks().beforeContentCreate("orders", this::processFlashSaleOrder);
    }

    /**
     * Orchestrates a global 2PC transaction across Redis Cluster (TCC) and PostgreSQL (AT).
     * XID is automatically propagated via Java 25 ScopedValue.
     */
    @AstraGlobalTransactional(name = "flash-sale-checkout", timeoutMills = 5000)
    public void processFlashSaleOrder(ContentPayload orderPayload) {
        String sku = orderPayload.getString("sku");
        int qty = orderPayload.getInt("quantity");

        // Branch 1: Reserve stock in Redis Cluster via Hash-Tag TCC (Phase 1 - Try)
        boolean reserved = deductStockTcc(sku, qty);
        if (!reserved) {
            throw new InsufficientStockException("Insufficient stock for SKU: " + sku);
        }

        // Branch 2: Persist order in PostgreSQL via Seata AT Mode.
        // If PostgreSQL fails or any downstream hook throws an exception,
        // Seata rolls back Postgres via undo_log AND invokes cancelDeduct() on Redis Cluster.
        orderService.saveOrder(orderPayload);
    }

    @AstraTccAction(
        name = "redisStockDeduct",
        confirmMethod = "confirmDeduct",
        cancelMethod = "cancelDeduct"
    )
    public boolean deductStockTcc(String sku, int qty) {
        // AstraRedisTccTemplate automatically wraps the resource key in Redis Hash Tags:
        // e.g., "{inventory:sku:IPHONE17}:available" & "{inventory:sku:IPHONE17}:frozen:{xid}"
        return redisTcc.tryFreeze("inventory:sku:" + sku, qty);
    }

    public boolean confirmDeduct(String sku, int qty) {
        return redisTcc.confirmFreeze("inventory:sku:" + sku, qty);
    }

    public boolean cancelDeduct(String sku, int qty) {
        return redisTcc.cancelFreeze("inventory:sku:" + sku, qty);
    }
}

```

### 2. Writing a Hot-Swappable Runtime Plugin (JAR or Wasm AOT Sandbox)

For dynamic modules deployed at runtime across a Kubernetes cluster without rebuilding or restarting Pods, implement `DynamicAstraPlugin` and upload the `.jar` or `.wasm` artifact via the Admin API / CLI:

```java
package io.astra.plugins.slug;

import io.astra.spi.plugin.DynamicAstraPlugin;
import io.astra.spi.plugin.RuntimePluginRegistry;
import io.astra.spi.plugin.SandboxPolicy;

@SandboxPolicy(
    maxExecutionFuel = 500_000L, // Enforced in Chicory Wasm AOT mode
    maxMemoryPages = 64          // 4MB Linear Memory Cap
)
public class CustomSlugGeneratorPlugin implements DynamicAstraPlugin {

    @Override
    public void register(RuntimePluginRegistry registry) {
        // Hot-swappable content transformation hook.
        // Automatically tracked and garbage-collected on plugin upgrade/unload.
        registry.addContentEnricher("articles", content -> {
            String title = content.getString("title");
            content.put("slug", SlugUtils.toSlug(title));
            return content;
        });
    }
}

```

---

## 📂 Repository & Module Structure

```text
astra-cms/
├── astra-bom/                     # Bill of Materials (Quarkus 3.x, Seata 2.x, Chicory, Vert.x)
├── astra-spi/                     # Zero-dependency SPI for Plugin Developers
├── astra-tx-seata/                # Quarkus Apache Seata 2.x Extension
│   ├── runtime/                   # Smart-Bypass DataSourceProxy, Hash-Tag Redis TCC Lua Engine,
│   │                              # and ScopedValueContextCore SPI Bridge
│   └── deployment/                # Jandex @BuildStep processors for @AstraTccAction & 2PC proxies
├── astra-plugin-engine/           # Hybrid Plugin Manager
│   ├── buildtime-loader/          # Quarkus Extension static wiring
│   ├── runtime-loader/            # Leak-free Dynamic JAR Loader & Chicory Wasm AOT Compiler
│   └── artifact-sync/             # S3/MinIO Artifact Repository & Redis Pub/Sub Cluster Hot-Swap
├── astra-core/                    # Zero-DDL JSONB Schema Engine, GIN Optimizer, L1/L2 Cache Bus, RBAC
├── astra-web-api/                 # RESTEasy Reactive APIs, GraphQL, gRPC & Control-Plane Admin APIs
├── astra-cli/                     # CLI tool (`astra build`, `astra plugin publish`, `astra start`)
├── astra-server/                  # Main Production Distribution (JDK 25 Gen-ZGC & GraalVM Native)
└── deploy/                        # Production Infrastructure
    ├── docker/                    # Local Docker Compose stack (Postgres, Redis Cluster, MinIO, Seata)
    └── helm/                      # Kubernetes Helm Charts (HPA, Seata Raft StatefulSet, PodDisruptionBudget)

```

---

## 🚀 Getting Started

### Prerequisites

* **JDK:** Java 25+ LTS (OpenJDK 25 with Generational ZGC or GraalVM for JDK 25)
* **Build Tool:** Apache Maven 3.9+
* **Database:** PostgreSQL 16+ (with partitioned `undo_log` table initialized for Seata AT mode)
* **Cache / State:** Redis 7.2+ (Standalone for dev / Redis Cluster for K8s production)
* **Object Storage:** S3-compatible storage (AWS S3 / MinIO) for multi-pod runtime plugin synchronization
* **Transaction Coordinator:** Apache Seata Server 2.x (Raft Cluster mode in production)

### 1. Clone & Spin Up Local Infrastructure

```bash
git clone [https://github.com/ZeroHostCMS/astra-cms.git](https://github.com/ZeroHostCMS/astra-cms.git)
cd astra-cms

# Spin up PostgreSQL 16, Redis Cluster, MinIO, and Apache Seata Server 2.x
docker compose -f deploy/docker/docker-compose-infra.yml up -d

```

### 2. Configure `application.yml`

```yaml
quarkus:
  datasource:
    db-kind: postgresql
    jdbc:
      url: jdbc:postgresql://localhost:5432/astra_cms
      min-size: 20
      max-size: 100
    username: astra
    password: ${ASTRA_DB_PASSWORD:secret}
  redis:
    hosts:
      - redis://localhost:7001
      - redis://localhost:7002
      - redis://localhost:7003
    client-type: cluster
  virtual-threads:
    enabled: true

astra:
  cache:
    l1-max-size: 50000
    invalidation-channel: astra:cluster:cache-evict
  plugins:
    storage-backend: s3 # 'local' for single-node dev, 's3' for Kubernetes multi-pod HA
    s3-bucket: astra-runtime-plugins
    hot-reload-channel: astra:cluster:plugin-sync
    wasm:
      aot-compile: true
      default-fuel-limit: 1000000
  seata:
    enabled: true
    smart-bypass: true
    application-id: astra-cms-core
    tx-service-group: astra_tx_raft_group

```

### 3. Run in Development Mode (with Live Coding)

```bash
./mvnw quarkus:dev

```

### 4. Build Production Distribution & Deploy to Kubernetes

```bash
# Option A: Build Standard JVM Distribution (Recommended for K8s HPA + Full JAR/Wasm Runtime Plugins)
./mvnw clean package -DskipTests
java -XX:+UseZGC -XX:+ZGenerational -Xshare:auto -jar astra-server/target/quarkus-app/quarkus-run.jar

# Option B: Build GraalVM Native Executable (Supports Mode A Build-time + Mode B Wasm Plugins)
./mvnw clean package -Pnative -DskipTests

# Deploy to Kubernetes via Helm
helm upgrade --install astra-cms ./deploy/helm/astra-cms \
  --namespace astra-prod --create-namespace \
  -f ./deploy/helm/values-production.yaml

```

---

## 🗺️ Enterprise Roadmap

* [x] **Phase 1: Cloud-Native Architecture & Core SPI** — Define zero-dependency `astra-spi`, Dual-Mode Plugin lifecycle, and K8s Multi-Pod synchronization specification.
* [ ] **Phase 2: Quarkus Seata 2.x Extension (`astra-tx-seata`)** — Implement `ScopedValueContextCore` SPI bridge, Smart-Bypass `AgroalDataSource` AT proxy, and Redis Cluster Hash-Tag TCC Lua Engine (`AstraRedisTccTemplate`).
* [ ] **Phase 3: Cluster-Synced Hybrid Plugin Engine** — Build Jandex build-time processor, Leak-Free JVM `PluginClassLoader`, Chicory Wasm AOT compiler with Fuel Metering, and S3 + Redis Pub/Sub hot-swap broadcaster.
* [ ] **Phase 4: Dynamic JSONB Content Engine & Coherent Cache** — PostgreSQL `JSONB` zero-DDL schema builder, GIN index optimizer, L1/L2 Cache Invalidation Bus, and REST/GraphQL/gRPC auto-generation.
* [ ] **Phase 5: Kubernetes Operator & Production Observability** — Official Helm charts, Seata Raft StatefulSet templates, OpenTelemetry distributed tracing (propagating XID + TraceID via `ScopedValue`), and Prometheus metrics.
* [ ] **Phase 6: Admin Studio & Transaction Control Center** — Modern Admin UI, real-time Seata 2PC & Redis TCC lock inspector, and Enterprise Plugin Marketplace.

---

## 🤝 Contributing

Contributions, distributed systems benchmarks, and plugin proposals are warmly welcome!

---

## 📄 License

Distributed under the **MIT License**. See `LICENSE` for more information.
