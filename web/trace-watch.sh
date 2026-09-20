#!/usr/bin/env bash
# Rewrites web/trace.json on a timer while the page polls it. Served over HTTP
# because a file:// page cannot fetch its neighbours.
set -euo pipefail
cd "$(dirname "$0")/.."

interval="${1:-3}"
port="${PORT:-8080}"

./bin/repo trace --out web/trace.json > /dev/null

python3 -m http.server "$port" --directory web --bind 127.0.0.1 > /dev/null 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT

url="http://localhost:${port}/trace.html"
echo "$url - refreshing every ${interval}s, Ctrl-C to stop"
browser_opener=""
case "$(uname -s)" in
  Darwin) browser_opener=open ;;
  Linux) browser_opener=xdg-open ;;
esac
if [ -n "$browser_opener" ] && command -v "$browser_opener" >/dev/null; then
  "$browser_opener" "$url" >/dev/null 2>&1 || true
fi

while true; do
  sleep "$interval"
  ./bin/repo trace --out web/trace.json > /dev/null
done
