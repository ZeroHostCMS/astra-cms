.PHONY: build test infra-up infra-down dev image k3d-up k3d-down demo

export PATH := $(HOME)/.local/bin:$(PATH)

build:            ## compile and package all modules
	./mvnw -q -DskipTests package

test:             ## run tests (Dev Services starts PostgreSQL in Docker)
	./mvnw -q test

infra-up:         ## PostgreSQL + Redis via docker compose for local dev
	docker compose -f deploy/docker/docker-compose.yml up -d --wait

infra-down:
	docker compose -f deploy/docker/docker-compose.yml down

dev: infra-up     ## Quarkus dev mode with live reload
	./mvnw -pl astra-server -am quarkus:dev

image: build      ## build the server container image
	docker build -f astra-server/src/main/docker/Dockerfile.jvm -t astra/server:dev .

k3d-up:           ## create k3d cluster, build, import, deploy, wait
	scripts/k3d-up.sh

k3d-down:
	k3d cluster delete astra

demo:             ## smoke test against http://localhost:8080 (or BASE=...)
	scripts/demo.sh $(BASE)
