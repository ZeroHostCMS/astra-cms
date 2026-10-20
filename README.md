
<div align="center">

# Astra CMS

**Next-Gen Java Quarkus Headless CMS for High-Performance Enterprise Custom Apps**  
*Hybrid Build-time & Dynamic Runtime Plugins • Native Apache Seata (2PC/TCC) Distributed Transactions • Modern Java 25+*

[![Java Version](https://img.shields.io/badge/Java-25%2B%20LTS-orange.svg)](https://openjdk.org/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.x-blue.svg)](https://quarkus.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-JSONB-blueviolet.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-TCC%20%26%20Cache-dc382d.svg)](https://redis.io/)
[![Apache Seata](https://img.shields.io/badge/Apache%20Seata-2PC%20%2F%20TCC-red.svg)](https://seata.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

---

## 📖 Overview

**Astra CMS** is an open-source, cloud-native Headless CMS and Enterprise Application Engine built on **Java Quarkus**. It is engineered from the ground up to solve two major bottlenecks in modern enterprise content and commerce platforms: **runtime performance** and **distributed data consistency**.

Most headless CMS platforms (such as Node.js-based engines or legacy Java CMSs) struggle when used as the backbone for transactional enterprise applications—either lacking real distributed transaction boundaries across databases and caches, or suffering from sluggish startup times, massive memory footprints, and rigid plugin ecosystems.

**Astra CMS** bridges this gap by combining:
1. **A Dual-Mode (Hybrid) Plugin Architecture:** Supporting both **Build-time Extensions** (for raw native speed and deep core/DB integration) and **Dynamic Runtime Plugins** (for hot-swapping business logic, webhooks, and custom modules on the fly without server restarts).
2. **Native Apache Seata (2PC / TCC) Integration:** Guaranteeing strict data consistency across **PostgreSQL** and **Redis** out of the box.
3. **Modern Java 25+ Architecture:** Leveraging Virtual Threads (JEP 491), Scoped Values, and Structured Concurrency for massive throughput with simple imperative code.

---

## 🏛️ Architecture Blueprint

```text
+-----------------------------------------------------------------------------+
|                            ASTRA-CMS API GATEWAY                            |
|          REST (Quarkus REST)    |    GraphQL    |    gRPC (Internal)        |
+-----------------------------------------------------------------------------+
                                        |
+-----------------------------------------------------------------------------+
|                         ASTRA CORE ENGINE (Quarkus)                         |
|  +-------------------+   +---------------------+   +---------------------+  |
|  |  Content Engine   |   |  Vert.x Event Bus   |   |  Security & RBAC    |  |
|  |  (Dynamic Schema) |   |  (Async Hooks/Flow) |   |  (OIDC / Multi-Ten) |  |
|  +-------------------+   +---------------------+   +---------------------+  |
+-----------------------------------------------------------------------------+
            |                                                |
+------------------------------------+  +-------------------------------------+
|     ASTRA TRANSACTION MANAGER      |  |    HYBRID PLUGIN ENGINE (Dual-Mode) |
|   Native Apache Seata Integration  |  |  1. Build-time (Jandex / Quarkus)   |
|   - AT Mode: Postgres DataSource   |  |  2. Runtime Hot-Swap:               |
|   - TCC Mode: Redis Lua Engine     |  |     - JVM Dynamic Loader (PF4J/SPI) |
|   - ScopedValue XID Propagation    |  |     - Sandboxed Wasm (Chicory)      |
+------------------------------------+  +-------------------------------------+
            |                                                |
+-----------------------------------------------------------------------------+
|                            DATA & INFRASTRUCTURE                            |
|    PostgreSQL (JSONB + Relational + undo_log)   |   Redis (L2 Cache & TCC)  |
+-----------------------------------------------------------------------------+

```

---

## ⚡ Core Pillars & Features

### 1. Dual-Mode Hybrid Plugin System (Build-time + Runtime)

Enterprise platforms require both uncompromising speed for core infrastructure and extreme flexibility for business logic. Astra CMS provides a unified `AstraPlugin` SPI that operates in two modes:

| Feature / Dimension | 🏗️ Mode A: Build-time Plugins | 🔥 Mode B: Dynamic Runtime Plugins |
| --- | --- | --- |
| **Mechanism** | Quarkus Build-time Augmentation (`@BuildStep` & **Jandex** bytecode indexing). | **JVM Dynamic ClassLoading** (PF4J-style JARs) & **WebAssembly (Chicory Wasm)** sandboxes. |
| **Deployment** | Bundled via `astra build` CLI (similar to Keycloak / Caddy). | **Zero-downtime Hot-Swap:** Upload/enable/disable via Admin UI or `/plugins` folder at runtime. |
| **Capabilities** | Full CDI (`@Inject`), Hibernate Entity registration, Seata Bytecode Proxies, Native Image support. | Custom REST routes, Content Lifecycle Hooks, Webhooks, Payment/Shipping adapters, Workflow rules. |
| **Isolation** | Shared Core ClassLoader (Maximum speed, zero serialization overhead). | Isolated ClassLoader per JAR or 100% crash-proof Wasm sandbox. |
| **Best Used For** | Heavy infrastructure modules, custom DB connectors, Seata 2PC transactional services. | Tenant-specific customizations, 3rd-party integrations, dynamic content transformers. |

---

### 2. Native 2PC Distributed Transactions (Apache Seata)

When using PostgreSQL alongside Redis (for inventory, rate-limiting, wallets, or state), traditional `MULTI/EXEC` or `afterCommit` hooks fail to prevent data drift during crashes. Astra CMS integrates **Apache Seata 2.x** natively into the Quarkus lifecycle:

* **PostgreSQL AT Mode (Auto-Compensation):**
* Automatically wraps Quarkus `AgroalDataSource` with Seata's `DataSourceProxy`.
* Parses SQL ASTs automatically to generate `before-image` and `after-image` snapshots in the PostgreSQL `undo_log` table—zero manual rollback code required.


* **Redis TCC Mode (Built-in Lua Engine):**
* Since Redis does not support XA/AT natively, Astra CMS ships with `AstraRedisTccTemplate`—an atomic, Lua-script-backed **Try-Confirm-Cancel (TCC)** engine on top of `quarkus-redis-client`.
* **Try:** Freezes resources/stock (`key:available` -> `key:frozen:{xid}`) and records transaction state.
* **Confirm:** Commits the reservation asynchronously in Phase 2 and cleans up freeze records.
* **Cancel:** Restores frozen values to `key:available`. Built-in state tracking prevents **Empty Rollback**, **Hanging (Try arriving after Cancel)**, and guarantees **Idempotency**.



---

### 3. Modern Java 25+ Concurrency Engine

Astra CMS is built for the latest Java LTS ecosystem to maximize hardware utilization without the complexity of reactive callback hell:

* **Unpinned Virtual Threads (JEP 491):** Every HTTP request, plugin hook, and Seata RPC/JDBC call runs on lightweight Virtual Threads without `synchronized` monitor pinning—delivering reactive-grade concurrency with clean, blocking-style imperative code.
* **Scoped Values (`ScopedValue`):** Replaces legacy `ThreadLocal` for propagating `Seata XID`, `TenantContext`, and `SecurityIdentity` safely and immutably across millions of virtual threads with zero memory leak risk.
* **Structured Concurrency (`StructuredTaskScope`):** Executes multi-plugin hooks and Phase-1 resource reservations in parallel. If any branch (PostgreSQL, Redis, or external gRPC) fails, sibling tasks are immediately short-circuited and a global Seata rollback is triggered.

---

### 4. High-Performance Hybrid Storage (PostgreSQL `JSONB` + Redis)

* **Zero-DDL Dynamic Schemas:** Creating or modifying a Content-Type in the CMS never runs blocking `ALTER TABLE` or `CREATE TABLE` DDL statements in production. Core metadata (`id`, `tenant_id`, `type`, `status`, `version`, `created_at`) lives in indexed relational columns, while dynamic fields are stored in binary `JSONB` indexed via high-speed **GIN (`jsonb_path_ops`)**.
* **Transaction-Aware Multi-Level Caching:** L1 In-Memory Cache (**Caffeine**) for schemas, permissions, and plugin metadata + L2 Distributed Cache (**Redis**) synchronized strictly with Seata transaction boundaries.

---

## Developer Experience (Code Examples)

### 1. Writing a Transactional Plugin (2PC across Postgres & Redis)

Plugins only need to depend on the ultra-lightweight `astra-spi` module:

```java
package io.astra.plugins.flashsale;

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
        // Register dynamic hooks or custom endpoints at runtime or build-time
        context.hooks().beforeContentCreate("orders", this::processFlashSaleOrder);
    }

    /**
     * Orchestrates a global 2PC transaction across PostgreSQL (AT) and Redis (TCC)
     */
    @AstraGlobalTransactional(name = "flash-sale-checkout", timeoutMills = 10000)
    public void processFlashSaleOrder(ContentPayload orderPayload) {
        String sku = orderPayload.getString("sku");
        int qty = orderPayload.getInt("quantity");

        // Branch 1: Reserve stock in Redis via TCC (Phase 1 - Try)
        boolean reserved = deductStockTcc(sku, qty);
        if (!reserved) {
            throw new InsufficientStockException("Out of stock for SKU: " + sku);
        }

        // Branch 2: Persist order content in PostgreSQL via Seata AT Mode
        // If this fails (or any subsequent plugin throws an exception),
        // Seata automatically rolls back Postgres via undo_log AND triggers cancelDeduct() on Redis!
        orderService.saveOrder(orderPayload);
    }

    @AstraTccAction(
        name = "redisStockDeduct",
        confirmMethod = "confirmDeduct",
        cancelMethod = "cancelDeduct"
    )
    public boolean deductStockTcc(String sku, int qty) {
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

### 2. Writing a Hot-Swappable Runtime Plugin

For dynamic modules loaded at runtime without rebuilding the server, implement `DynamicAstraPlugin` and drop the `.jar` (or `.wasm` module) into the `/plugins/runtime` directory:

```java
public class CustomSlugGeneratorPlugin implements DynamicAstraPlugin {

    @Override
    public void register(RuntimePluginRegistry registry) {
        // Hot-swappable content transformation hook
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
├── astra-bom/                     # Bill of Materials (Quarkus, Seata, Vert.x versions)
├── astra-spi/                     # Zero-dependency SPI for Plugin Developers
├── astra-tx-seata/                # Quarkus Seata Extension
│   ├── runtime/                   # Agroal DataSourceProxy, Redis TCC Lua Engine, ScopedValue XID
│   └── deployment/                # Jandex @BuildStep scanners for @AstraTccAction & @AstraGlobalTransactional
├── astra-plugin-engine/           # Hybrid Plugin Manager
│   ├── buildtime-loader/          # Quarkus Extension build-time wiring
│   └── runtime-loader/            # Dynamic JAR ClassLoader & Chicory Wasm Sandbox
├── astra-core/                    # Dynamic JSONB Schema Engine, Query Builder, Multi-tenancy, RBAC
├── astra-web-api/                 # RESTEasy Reactive APIs, GraphQL & Hot-Swap Admin Endpoints
├── astra-cli/                     # CLI tool (`astra build`, `astra plugin install`, `astra start`)
└── astra-server/                  # Main Runnable Distribution (JVM & GraalVM Native)

```

---

## Getting Started

### Prerequisites

* **JDK:** Java 25+ (OpenJDK / GraalVM for JDK 25)
* **Build Tool:** Apache Maven 3.9+
* **Database:** PostgreSQL 15+ (with `undo_log` table initialized for Seata AT mode)
* **Cache / State:** Redis 7.0+
* **Transaction Coordinator:** Apache Seata Server 2.x

### 1. Clone & Spin Up Infrastructure

```bash 
git clone [https://github.com/ZeroHostCMS/astra-cms/astra-cms.git](https://github.com/ZeroHostCMS/astra-cms/astra-cms.git)
cd astra-cms

# Spin up PostgreSQL, Redis, and Apache Seata Server via Docker Compose
docker compose -f docker/docker-compose-infra.yml up -d

```

### 2. Configure `application.yml`

```yaml
quarkus:
  datasource:
    db-kind: postgresql
    jdbc:
      url: jdbc:postgresql://localhost:5432/astra_cms
    username: astra
    password: secret
  redis:
    hosts: redis://localhost:6379
  virtual-threads:
    enabled: true

astra:
  plugins:
    runtime-dir: ./plugins/runtime
    hot-reload: true
    wasm-sandbox-enabled: true
  seata:
    enabled: true
    application-id: astra-cms-core
    tx-service-group: astra_tx_group

```

### 3. Run in Development Mode (with Live Coding)

```bash
./mvnw quarkus:dev

```

### 4. Build Production Distribution (with Build-time Plugins)

Place build-time plugins in `./plugins/build/` and run:

```bash
# Compiles and wires all build-time plugins & Seata proxies via Jandex
./mvnw clean package -DskipTests

# Or compile to a GraalVM Native Executable
./mvnw clean package -Pnative -DskipTests

```

---

## Roadmap

* [x] **Phase 1: Architecture & Core SPI** — Define `astra-spi`, Hybrid Plugin lifecycle, and 2PC design.
* [ ] **Phase 2: Quarkus Seata Extension (`astra-tx-seata`)** — Implement `AgroalDataSource` Seata AT proxy, `ScopedValue` XID propagation, and Redis TCC Lua Script engine.
* [ ] **Phase 3: Hybrid Plugin Engine** — Build Jandex build-time processor + Dynamic Runtime JAR/Wasm loader with hot-swap Admin APIs.
* [ ] **Phase 4: Dynamic Content Engine** — PostgreSQL `JSONB` dynamic schema builder, GIN index optimizer, and REST/GraphQL auto-generation.
* [ ] **Phase 5: Admin Studio & Ecosystem** — Modern Admin UI, Transaction Monitor Dashboard (for inspecting active Seata branches & Redis TCC locks), and Official Plugin Marketplace.

---

## 🤝 Contributing

Contributions, architecture discussions, and plugin proposals are warmly welcome!
---

## License

Distributed under the **MIT License**.

```
