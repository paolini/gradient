# syntax=docker/dockerfile:1
FROM node:22-bookworm-slim AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

FROM python:3.14-slim-bookworm AS python-deps
COPY --from=ghcr.io/astral-sh/uv:0.13.5 /uv /usr/local/bin/uv
ENV UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev --no-install-project

FROM python:3.14-slim-bookworm AS runtime
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
RUN groupadd --gid 10001 gradient \
    && useradd --uid 10001 --gid gradient --no-create-home gradient
COPY --from=python-deps /app/.venv /app/.venv
COPY gradient/ ./gradient/
COPY migrations/ ./migrations/
COPY alembic.ini ./
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/gradient-entrypoint
COPY --from=frontend /build/dist ./frontend/dist/
USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3).close()"]
# Un solo worker contiene il consumo di RAM. Migrazioni opzionali via GRADIENT_AUTO_MIGRATE.
# I log di accesso possono contenere i codici OAuth nelle query delle callback.
ENTRYPOINT ["gradient-entrypoint"]
CMD ["uvicorn", "gradient.app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--no-proxy-headers"]
