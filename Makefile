COMPOSE_DEV  := docker compose -f web/infra/compose.yml
COMPOSE_PROD := docker compose -f web/infra/compose.prod.yml

.PHONY: dev dev-down prod prod-down logs build test fmt tidy \
        android android-test android-install

# Local stack: dev auth, no attestation, no TLS. http://127.0.0.1:8080
# NFCIT_PORT=8090 make dev publishes it elsewhere.
dev:
	$(COMPOSE_DEV) up --build -d

dev-down:
	$(COMPOSE_DEV) down

# Production stack: Caddy, real domain, Telegram OIDC, Key Attestation.
prod:
	$(COMPOSE_PROD) up --build -d

prod-down:
	$(COMPOSE_PROD) down

logs:
	$(COMPOSE_DEV) logs -f api

build:
	cd web/server && CGO_ENABLED=0 go build -trimpath -o ../../bin/nfcit ./cmd/nfcit

test:
	cd web/server && go test ./...

# Store tests need a real database. Runs against the local dev stack, and wipes
# its schema on every run.
test-store:
	cd web/server && NFCIT_TEST_DB=postgres://nfcit:nfcit@127.0.0.1:5432/nfcit?sslmode=disable go test ./internal/store

fmt:
	cd web/server && gofmt -w cmd internal

tidy:
	cd web/server && go mod tidy

# The app. Signing in against `make dev` needs NFCIT_AUTH_MODE=dev on the server
# and nfcit.baseUrl.debug in android/local.properties.
android:
	cd android && ./gradlew assembleDebug

android-test:
	cd android && ./gradlew test

android-install:
	cd android && ./gradlew installDebug
