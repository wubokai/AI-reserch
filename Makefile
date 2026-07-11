SHELL := /bin/sh

.PHONY: help install install-web install-analytics lint typecheck test build seed dev-up dev-down smoke clean

help:
	@echo "install     Install Web and Analytics development dependencies"
	@echo "lint        Run Web, Python, and Java static checks"
	@echo "typecheck   Run TypeScript and Python type checks"
	@echo "test        Run all unit tests"
	@echo "build       Build all applications available on this machine"
	@echo "seed        Apply Flyway and verify deterministic Mock seed data"
	@echo "dev-up      Start the complete Docker Compose stack"
	@echo "dev-down    Stop the Docker Compose stack"
	@echo "smoke       Verify all running health endpoints"

install: install-web install-analytics

install-web:
	pnpm install --frozen-lockfile

install-analytics:
	python3.12 -m venv apps/analytics/.venv
	apps/analytics/.venv/bin/python -m pip install --upgrade pip
	apps/analytics/.venv/bin/python -m pip install -e 'apps/analytics[dev]'

lint:
	pnpm lint:web
	cd apps/analytics && .venv/bin/ruff check . && .venv/bin/ruff format --check .
	cd apps/api && ./mvnw --batch-mode --no-transfer-progress verify -DskipTests

typecheck:
	pnpm typecheck:web
	cd apps/analytics && .venv/bin/mypy src tests

test:
	pnpm test:web
	cd apps/analytics && .venv/bin/pytest
	cd apps/api && ./mvnw --batch-mode --no-transfer-progress test

build:
	pnpm build:web
	cd apps/api && ./mvnw --batch-mode --no-transfer-progress package -DskipTests

seed:
	./scripts/seed-data.sh

dev-up:
	./scripts/dev-up.sh

dev-down:
	./scripts/dev-down.sh

smoke:
	./scripts/smoke-test.sh

clean:
	rm -rf apps/web/.next apps/web/coverage apps/analytics/.pytest_cache apps/analytics/.mypy_cache apps/analytics/.ruff_cache apps/api/target
