#!/usr/bin/env bash
# Convenience launcher: sets up a venv, installs deps, seeds .env, starts the app.
set -euo pipefail

cd "$(dirname "$0")"

PYTHON=${PYTHON:-python3}
VENV=${VENV:-.venv}
PORT=${PORT:-8000}

if [ ! -d "$VENV" ]; then
  echo "→ creating virtualenv in $VENV"
  "$PYTHON" -m venv "$VENV"
fi

# shellcheck disable=SC1091
source "$VENV/bin/activate"

echo "→ installing dependencies"
pip install --quiet --upgrade pip
pip install --quiet -r requirements.txt

if [ ! -f .env ]; then
  echo "→ creating .env with fresh secrets"
  cp .env.example .env
  KEY=$(python -m backend.app.security --new-key)
  SECRET=$(python -m backend.app.security --new-secret)
  # Portable in-place edit (BSD and GNU sed disagree about -i).
  python - "$KEY" "$SECRET" <<'PY'
import pathlib, sys
key, secret = sys.argv[1], sys.argv[2]
path = pathlib.Path(".env")
text = path.read_text()
text = text.replace("ENCRYPTION_KEY=", f"ENCRYPTION_KEY={key}", 1)
text = text.replace("SESSION_SECRET=", f"SESSION_SECRET={secret}", 1)
path.write_text(text)
PY
  echo "  .env created. Add your bank API keys to it when you're ready to connect."
fi

mkdir -p data
echo "→ starting on http://localhost:${PORT}"
exec uvicorn backend.app.main:app --host 0.0.0.0 --port "$PORT" "$@"
