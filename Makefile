.PHONY: build test infra-up infra-down dev image k3d-up k3d-down demo demo-iam demo-seed demo-express demo-stms demo-reset

export PATH := $(HOME)/.local/bin:$(PATH)

# The build targets Java 25. On macOS, pick an installed JDK 25 even when the shell's
# JAVA_HOME points at an older JDK; elsewhere, keep whatever JAVA_HOME already is.
JDK25 := $(shell /usr/libexec/java_home -v 25 2>/dev/null)
ifneq ($(JDK25),)
export JAVA_HOME := $(JDK25)
endif

build:            ## compile and package all modules
	./mvnw -q -DskipTests package

test:             ## run tests (Dev Services starts PostgreSQL in Docker)
	./mvnw -q test

infra-up:         ## PostgreSQL, Redis and Keycloak (:8180) via docker compose for local dev
	docker compose -f deploy/docker/docker-compose.yml up -d --wait
	scripts/keycloak-compose-dev.sh

infra-down:
	docker compose -f deploy/docker/docker-compose.yml down

dev: infra-up     ## Quarkus dev mode with live reload
	./mvnw -pl astra-server -am quarkus:dev $(DEV_ARGS)

image: build      ## build the server container image
	docker build -f astra-server/src/main/docker/Dockerfile.jvm -t astra/server:dev .

k3d-up:           ## create k3d cluster, build, import, deploy, wait
	scripts/k3d-up.sh

k3d-down:
	k3d cluster delete astra

demo:             ## smoke test against http://localhost:8080 (or BASE=...)
	scripts/demo.sh $(BASE)

demo-iam:         ## end-to-end IAM checks with real Keycloak tokens (or BASE=...)
	scripts/demo-iam.sh $(BASE)

demo-seed:        ## seed the Astra Express demo tenants (BASE=http://localhost:8080)
	scripts/demo-seed.sh $(BASE)

demo-express:     ## scene-by-scene terminal demo; SCENES="3 4" picks scenes, PAUSE=0 runs straight through
	scripts/demo-express.sh $(or $(BASE),http://localhost:8080) $(SCENES)

demo-stms:        ## STMS routing demo (Timefold, live replanning, mock-LLM copilot); SCENES="3 4", PAUSE=0, STMS_DATE=YYYY-MM-DD
	scripts/demo-stms.sh $(or $(BASE),http://localhost:8080) $(SCENES)

demo-reset:       ## delete the demo tenants from the Docker Compose database
	scripts/demo-reset.sh
