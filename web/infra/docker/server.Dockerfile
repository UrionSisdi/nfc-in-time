# Build context is web/: the image carries both the API binary and the landing
# page it serves.
FROM golang:1.26-alpine AS build

WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/nfcit ./cmd/nfcit

FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /out/nfcit /usr/local/bin/nfcit
COPY public /srv/web/public

ENV NFCIT_STATIC_DIR=/srv/web/public \
    NFCIT_ADDR=:8080

EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/usr/local/bin/nfcit"]
