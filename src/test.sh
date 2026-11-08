#filecove.test: some test cases written by claude

set -u

DIR="$(cd "$(dirname "$0")" && pwd)" # the folder this script lives in
TMP="$(mktemp -d)"                   # e.g. /tmp/tmp.Xa81kQ
PORT=8899                            # not 8800, so a real server can keep running
TOKEN=test-token-123
PASS=0
FAIL=0
SERVER_PID=""

cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT # runs however the script ends, even on Ctrl+C

pass() {
  echo "  PASS  $1"
  PASS=$((PASS + 1))
}
fail() {
  echo "  FAIL  $1"
  echo "        $2"
  FAIL=$((FAIL + 1))
}

# check "what we're testing" "text that should appear" "actual output"
check() {
  if [[ "$3" == *"$2"* ]]; then
    pass "$1"
  else
    fail "$1" "expected to see: '$2'   got: '$3'"
  fi
}

# same "what we're testing" FILE1 FILE2: pass if the files are byte-identical
same() {
  if cmp -s "$2" "$3"; then pass "$1"; else fail "$1" "$2 and $3 differ"; fi
}

make_device() { # make_device NAME -> fake home with a config for the test server
  mkdir -p "$TMP/$1/.config/filecove" "$TMP/$1/work"
  cat >"$TMP/$1/.config/filecove/config.properties" <<EOF
server=http://127.0.0.1:$PORT
token=$TOKEN
device=$1
EOF
}

# Run the client as one of the fake devices, from inside that device's work
# folder. The ( ) makes a subshell so the cd doesn't leak out.
tower() { (cd "$TMP/tower/work" && java -Duser.home="$TMP/tower" -cp "$TMP/classes" Client "$@" 2>&1); }
laptop() { (cd "$TMP/laptop/work" && java -Duser.home="$TMP/laptop" -cp "$TMP/classes" Client "$@" 2>&1); }

# raw METHOD PATH [TOKEN] -> prints only the HTTP status code.
# --path-as-is sends the path EXACTLY as written; normally curl would
# "tidy up" things like ../ first, which would hide what we're testing.
raw() {
  curl -s --path-as-is -o /dev/null -w '%{http_code}' -X "$1" \
    -H "Authorization: Bearer ${3-$TOKEN}" $([ "$1" = PUT ] && echo "--data-binary x") \
    "http://127.0.0.1:$PORT$2"
}

echo "== compile =="
for tool in java javac curl; do
  command -v "$tool" >/dev/null || {
    echo "'$tool' not found; install it first"
    exit 1
  }
done
# Compile once into a temp folder. Errors show the file and line number.
if javac -d "$TMP/classes" "$DIR/Server.java" "$DIR/Client.java"; then
  pass "both files compile"
else
  echo "Fix the compile errors above, then run this again."
  exit 1
fi

echo "== server startup =="
out=$(FILECOVE_TOKEN= FILECOVE_DATA="$TMP/data" timeout 10 java -cp "$TMP/classes" Server 2>&1)
check "server refuses to start without a token" "FILECOVE_TOKEN is not set" "$out"

FILECOVE_TOKEN=$TOKEN FILECOVE_DATA="$TMP/data" FILECOVE_PORT=$PORT \
  java -cp "$TMP/classes" Server >"$TMP/server.log" 2>&1 &
SERVER_PID=$!

# Wait up to 10 seconds for it to start answering.
for _ in $(seq 100); do
  [ "$(raw GET /files/)" != 000 ] && break # 000 = curl couldn't connect
  sleep 0.1
done
if ! kill -0 "$SERVER_PID" 2>/dev/null; then
  echo "  FAIL  the server crashed while starting. Its output:"
  cat "$TMP/server.log"
  exit 1
fi
pass "server is running on port $PORT"

make_device tower
make_device laptop

echo "== basics =="
check "wrong token is rejected (401)" "401" "$(raw GET /files/ wrong-token)"
check "no token is rejected (401)" "401" "$(raw GET /files/ '')"
check "unknown endpoint gives 404" "404" "$(raw GET /nothing)"
check "client shows help" "usage: fs" "$(tower --help)"
check "client rejects unknown commands" "unknown command" "$(tower frobnicate)"
