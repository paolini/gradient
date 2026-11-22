# syntax=docker/dockerfile:1
# Stadio di build: JDK + Clojure CLI per backend e ClojureScript, Node per le dipendenze npm.
FROM clojure:temurin-25-tools-deps-trixie-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY deps.edn build.clj ./
RUN --mount=type=cache,target=/root/.m2 clojure -P && clojure -P -T:uberjar
COPY frontend/package.json frontend/package-lock.json frontend/shadow-cljs.edn ./frontend/
RUN --mount=type=cache,target=/root/.npm --mount=type=cache,target=/root/.m2 \
    cd frontend && npm ci
COPY frontend/ ./frontend/
RUN --mount=type=cache,target=/root/.m2 cd frontend && npx shadow-cljs release app
COPY src/ ./src/
COPY resources/ ./resources/
RUN --mount=type=cache,target=/root/.m2 clojure -T:uberjar uber

FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app
RUN groupadd --gid 10001 gradient \
    && useradd --uid 10001 --gid gradient --no-create-home gradient \
    && mkdir -p /data && chown 10001:10001 /data
COPY --from=build /build/target/gradient.jar ./gradient.jar
COPY --from=build /build/frontend/public/ ./frontend/public/
USER 10001:10001
ENV HTTP_HOST=0.0.0.0 \
    HTTP_PORT=8000 \
    DATOMIC_STORAGE_DIR=/data \
    GRADIENT_STATIC_DIR=/app/frontend/public \
    JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xmx160m -Xss512k -XX:TieredStopAtLevel=1 --sun-misc-unsafe-memory-access=allow"
# Datomic Local salva i dati su file: la directory /data va montata come volume persistente.
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8000 && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200 OK' <&3"]
CMD ["java", "-jar", "/app/gradient.jar"]
