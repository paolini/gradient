#!/bin/sh
set -eu

# Le migrazioni automatiche riguardano l'avvio del server, non i comandi manuali.
if [ "${1:-}" = "uvicorn" ]; then
    case "${GRADIENT_AUTO_MIGRATE:-false}" in
        1|true|TRUE)
            echo "Applicazione delle migrazioni prima dell'avvio di Gradient…"
            alembic upgrade head
            ;;
        0|false|FALSE|"") ;;
        *)
            echo "GRADIENT_AUTO_MIGRATE deve essere true, false, 1 o 0." >&2
            exit 64
            ;;
    esac
fi

exec "$@"
