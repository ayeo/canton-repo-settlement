#!/usr/bin/env bash
# The complete demo: a bank borrowing 349,624,375.00
# against 365kk of a German government bond, settling the day it is agreed and
# repurchasing on the adjusted contractual date; interest depends on the term.
#
# Against a freshly bootstrapped topology:
#   make clean && make presentation
set -euo pipefail
cd "$(dirname "$0")/.."

pkill -f "repo-cli.jar --as .* watch" 2>/dev/null || true
rm -f .repo-refusals.log

logs=$(mktemp -d); echo "desk logs: $logs"
step() { echo; echo "\$ $*"; "$@"; }

for role in icsd centralbank AlphaBank BravoBank CharlieBank; do
  ./bin/repo --as "$role" watch > "$logs/$role.log" 2>&1 &
done
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT

for role in icsd centralbank AlphaBank BravoBank CharlieBank; do
  waited=0
  until grep -q "taking instructions" "$logs/$role.log" 2>/dev/null; do
    sleep 1; waited=$((waited+1))
    if [ $waited -ge 45 ]; then echo "DESK $role DID NOT START:"; tail -5 "$logs/$role.log"; exit 1; fi
  done
done
echo "all five desks are up"

await() {
  local what="$1" limit="${2:-90}" waited=0
  until grep -qh -- "$what" "$logs"/*.log 2>/dev/null; do
    sleep 1; waited=$((waited+1))
    if [ $waited -ge $limit ]; then echo "GAVE UP WAITING FOR: $what"; tail -n 4 "$logs"/*.log; exit 1; fi
  done
}

# Exit 2 means the desk has not observed both allocations (or the date) yet.
# Exit 1 is a failure, including an unknown submission outcome: do not mask it.
settle_when_ready() {
  local role="$1" trade="$2" attempt status request_id
  request_id=$(uuidgen)
  for ((attempt=0; attempt<90; attempt++)); do
    if ./bin/repo --as "$role" settle "$trade" --request-id "$request_id"; then
      return 0
    else
      status=$?
    fi
    if [ "$status" -ne 2 ]; then return "$status"; fi
    sleep 1
  done
  echo "SETTLEMENT DID NOT BECOME READY: $role $trade"
  return 1
}

echo; echo "=== market: registration, issuance, funding, opening the day ==="
make market
echo; echo "=== both lenders publish their schedules ==="
make policy
echo; echo "=== the dealer sets aside the bond and quotes 365kk to both ==="
make repo

echo; echo "=== the desks race for the quote ==="
await "allocated .*:opening"
grep -hE "accepted|refused|pulled|allocated" "$logs"/*.log | tail -n 8 | sed 's/^ *//; s/^/  /'

winner=BravoBank
./bin/repo --as BravoBank trades | grep -q "REPO-1" || winner=CharlieBank

step ./bin/repo --as AlphaBank trades

echo; echo "=== $winner won and settles the opening leg ==="
settle_when_ready "$winner" REPO-1
await "settled the opening leg"

step ./bin/repo --as AlphaBank trades
back=$(./bin/repo --as AlphaBank trades | sed -n 's/.*repurchase \([0-9-]\{10\}\).*/\1/p' | head -1)
if [ -z "$back" ]; then
  echo "COULD NOT READ THE REPURCHASE DATE - the opening leg did not settle:"
  ./bin/repo --as AlphaBank trades
  exit 1
fi
echo; echo "=== the market rolls to the repurchase date: $back ==="
make roll-date TO="$back"

echo; echo "=== the dealer waits for both closing holds and settles ==="
settle_when_ready AlphaBank REPO-1
# The submitting desk has already applied the confirmed closing transaction.
remaining=$(./bin/repo --as AlphaBank trades)
if printf '%s\n' "$remaining" | grep -q "REPO-1"; then
  echo "CLOSING DID NOT REMOVE REPO-1 FROM THE DEALER'S BOOK"
  exit 1
fi
await "settled the closing leg of REPO-1"

echo; echo "=== final state ==="
step ./bin/repo --as AlphaBank positions
step ./bin/repo --as AlphaBank cash
step ./bin/repo --as BravoBank cash
step ./bin/repo --as CharlieBank cash
echo; echo "refusals: $(wc -l < .repo-refusals.log 2>/dev/null | tr -d ' ' || echo 0)"
cat .repo-refusals.log 2>/dev/null || true
