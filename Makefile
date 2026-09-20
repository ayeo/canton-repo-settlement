# Canton repo PoC - a bilateral repurchase agreement settled across two
# independent registers on a distributed topology.

# The Canton version lives in .env, because docker compose reads it too.
include .env
export CANTON_VERSION

# The Daml SDK only compiles the model; Canton is fetched separately so the
# nodes can run a newer release than the SDK ships.
SDK_VERSION := 3.4.11

CACHE      := .cache
CANTON_TGZ := $(CACHE)/canton-open-source-$(CANTON_VERSION).tar.gz
CANTON_DIR := $(CACHE)/canton-open-source-$(CANTON_VERSION)
CANTON_JAR := $(CANTON_DIR)/lib/canton-open-source-$(CANTON_VERSION).jar
CANTON_URL := https://github.com/digital-asset/canton/releases/download/v$(CANTON_VERSION)/canton-open-source-$(CANTON_VERSION).tar.gz

# Resolve JDK 21 only when a Java command runs, on macOS or Linux.
# Keep support for an explicit `make JDK21=/path/to/jdk ...` override.
export JDK21
JAVA21 := ./scripts/with-java21.sh java

# app/.mvn/maven.config points Maven at app/settings.xml (see there for why).
MVN := ../scripts/with-java21.sh mvn -B

# Overridable on the command line:
#   make repo QTY=400000000 RATE=0.0290 DAYS=1
#   make desk ROLE=BravoBank
#   make roll-date TO=2026-09-17
# Defaults for the scenario run by scripts/presentation.sh.
TRADE   ?= REPO-1
ISIN    ?= DE0001102580
QTY     ?= 365000000
PRICE   ?= 98.75
HAIRCUT ?= 0.03
RATE    ?= 0.035
DAYS    ?= 1
SETTLES ?= 0
ROLE    ?= a
TO      ?=

# A script named on the command line is run instead of opening a session:
#   make console canton/scripts/fake-register.scala
SCRIPT := $(firstword $(filter %.canton %.scala,$(MAKECMDGOALS)))

.DEFAULT_GOAL := help
.PHONY: help version build test unit codegen cli image up wait bootstrap check \
	market policy repo desk roll-date trace watch-trace presentation \
	console ps logs down clean FORCE

help:  ## List available targets
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk -F':.*?## ' '{printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2}'

version:  ## Show the Canton and Daml versions in use
	@echo "  Canton (nodes):   $(CANTON_VERSION)"
	@echo "  Daml SDK (model): $(SDK_VERSION)"

build:  ## Compile the model and tests into DARs
	cd canton && daml build --all

test: build  ## Run the model tests (no infrastructure needed)
	cd canton/test && daml test

unit:  ## Run the Java unit tests
	cd app && $(MVN) -q test

codegen: build  ## Generate the Java classes from the model and compile them
	cd app && $(MVN) -q compile

$(CANTON_TGZ):
	@mkdir -p $(CACHE)
	@echo "downloading Canton $(CANTON_VERSION) (~271 MB)..."
	curl -fsSL -o $@ $(CANTON_URL)

$(CANTON_JAR): $(CANTON_TGZ)
	tar xzf $< -C $(CACHE) canton-open-source-$(CANTON_VERSION)/lib/canton-open-source-$(CANTON_VERSION).jar
	@touch $@

docker/canton.jar: $(CANTON_JAR)
	cp $< $@

image: docker/canton.jar  ## Build the Canton image
	docker compose build

up: image  ## Start every node in its own container
	docker compose up -d --remove-orphans
	@$(MAKE) --no-print-directory wait

wait:
	@echo "waiting for all nodes to become healthy..."
	@until [ "$$(docker compose ps --format '{{.Health}}' | grep -c healthy)" -eq 8 ]; do \
		docker compose ps --format '{{.Status}}' | grep -qE 'Exited|Restarting' \
			&& { echo "a container died:"; docker compose ps; exit 1; }; \
		sleep 3; \
	done
	@echo "all nodes ready"

bootstrap: build $(CANTON_JAR)  ## Bootstrap the synchronizer, upload the model, allocate parties
	$(JAVA21) -jar $(CANTON_JAR) run canton/bootstrap.canton -c canton/conf/remote.conf --no-tty

check: $(CANTON_JAR)  ## Ask every node the same questions, from a remote console
	$(JAVA21) -jar $(CANTON_JAR) run canton/scripts/topology-check.canton -c canton/conf/remote.conf --no-tty

cli: codegen  ## Build the command line into a single jar
	cd app && $(MVN) -q package

market:  ## Register the bond, issue it, fund the banks, open the settlement day
	./bin/repo --as icsd register --isin $(ISIN) --issuer "Federal Republic of Germany"
	./bin/repo --as icsd issue --isin $(ISIN) --to AlphaBank
	./bin/repo --as centralbank fund --to AlphaBank --amount 50000000
	./bin/repo --as centralbank fund --to BravoBank --amount 500000000
	./bin/repo --as centralbank fund --to CharlieBank --amount 400000000
	./bin/repo --as centralbank end-of-day

policy:  ## Both lenders publish their collateral schedules
	./bin/repo --as BravoBank publish --min-haircut 0.02 --max-qty 500000000 --max-cash 500000000 --rate 0.0325
	./bin/repo --as CharlieBank publish --min-haircut 0.03 --max-qty 500000000 --max-cash 400000000 --rate 0.0350

repo:  ## Quote a repo to the lenders (TRADE= ISIN= QTY= PRICE= HAIRCUT= RATE= DAYS= SETTLES=)
	./bin/repo --as AlphaBank collateral --trade $(TRADE) --isin $(ISIN) --qty $(QTY)
	./bin/repo --as AlphaBank propose --trade $(TRADE) --isin $(ISIN) --qty $(QTY) --price $(PRICE) \
		--haircut $(HAIRCUT) --rate $(RATE) --days $(DAYS) --settles $(SETTLES)

desk:  ## Run one institution's actor (ROLE=ICSD|CentralBank|AlphaBank|BravoBank|CharlieBank)
	./bin/repo --as $(ROLE) watch

roll-date:  ## The central bank's end of day (TO=YYYY-MM-DD for several)
	./bin/repo --as centralbank end-of-day $(if $(TO),--to $(TO),)

watch-trace:  ## Serve the trace page and keep its data current (Ctrl-C to stop)
	./web/trace-watch.sh

trace:  ## Write the trace data the page reads
	./bin/repo trace --out web/trace.json

presentation: up bootstrap cli  ## The demo in full: 349kk borrowed against 365kk of paper, repurchased the next day
	./scripts/presentation.sh

console: $(CANTON_JAR)  ## Canton console on the running nodes; name a script to run one instead
	$(JAVA21) -jar $(CANTON_JAR) $(if $(SCRIPT),run $(SCRIPT) --no-tty) -c canton/conf/remote.conf

# The file named after console is an argument, not a target of its own.
%.canton %.scala: FORCE
	@:

ps:  ## Container status
	docker compose ps

logs:  ## Follow the Canton node logs
	docker compose logs -f sequencer mediator icsd centralbank alpha bravo charlie

down:  ## Stop the containers, keep the data
	docker compose down --remove-orphans

clean:  ## Stop and delete everything including the database (keeps the Canton download)
	docker compose down -v --remove-orphans
	rm -f .repo-refusals.log web/trace.json
	rm -rf .repo-courier
	rm -rf canton/model/.daml canton/test/.daml app/target log docker/canton.jar ledger.properties

FORCE:
