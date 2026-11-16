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
if javac -d "$TMP/classes" "$DIR/../src/Server.java" "$DIR/../src/Client.java"; then
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
check "client shows help" "usage: cove" "$(tower --help)"
check "client rejects unknown commands" "unknown command" "$(tower frobnicate)"

#ls and file search tests
echo "== ls =="
check "ls on an empty server" "no files" "$(tower ls)"

#push and file handling tests:
echo "== push =="
echo "version one" >"$TMP/tower/work/notes.txt"
touch -d "2020-01-01 12:00" "$TMP/tower/work/notes.txt"
check "push a file" "pushed" "$(tower push notes.txt)"
check "ls shows it with the device name" "tower" "$(tower ls)"
check "push into a folder (remote ending in /)" "school/notes.txt" "$(tower push notes.txt school/)"
check "ls with a prefix filter" "school/notes.txt" "$(tower ls school/)"
check "pushing a missing local file explains why" "is not a file" "$(tower push nope.txt)"
check "upload path with .. is refused (400)" "400" "$(raw PUT /files/../evil)"
check "upload path with ../ inside is refused" "400" "$(raw PUT /files/a/../../evil)"
check "absolute upload path is refused" "400" "$(raw PUT /files//etc/evil)"

#pull test
echo "== pull =="
laptop pull notes.txt >/dev/null
same "laptop pulls identical contents" "$TMP/tower/work/notes.txt" "$TMP/laptop/work/notes.txt"
check "pull keeps the original modified time" \
  "$(stat -c %Y "$TMP/tower/work/notes.txt")" "$(stat -c %Y "$TMP/laptop/work/notes.txt")"

echo "spaces" >"$TMP/tower/work/my notes.txt"
tower push "my notes.txt" >/dev/null
laptop pull "my notes.txt" >/dev/null
same "file name with spaces survives the trip" "$TMP/tower/work/my notes.txt" "$TMP/laptop/work/my notes.txt"
check "ls shows 'my notes.txt', not 'my%20notes.txt'" "my notes.txt" "$(tower ls)"

echo "accents" >"$TMP/tower/work/résumé.txt"
tower push "résumé.txt" >/dev/null
laptop pull "résumé.txt" >/dev/null
same "non-English file name survives the trip" "$TMP/tower/work/résumé.txt" "$TMP/laptop/work/résumé.txt"

# 3 MB of random bytes: random data proves binary files (images, PDFs)
# aren't mangled, and the size makes the copy loops go around many times.
head -c 3000000 /dev/urandom >"$TMP/tower/work/big.bin"
tower push big.bin >/dev/null
laptop pull big.bin >/dev/null
same "3 MB binary file arrives byte-for-byte" "$TMP/tower/work/big.bin" "$TMP/laptop/work/big.bin"

: >"$TMP/tower/work/empty.txt"
tower push empty.txt >/dev/null
laptop pull empty.txt >/dev/null
same "empty file survives the trip" "$TMP/tower/work/empty.txt" "$TMP/laptop/work/empty.txt"

mkdir -p "$TMP/laptop/work/sub"
laptop pull notes.txt sub >/dev/null
check "pull into an existing folder" "version one" "$(cat "$TMP/laptop/work/sub/notes.txt" 2>&1)"

check "pulling a missing file explains why" "404" "$(laptop pull nope.txt)"
check "sneaky hash is refused (400)" "400" "$(raw GET '/files/x?hash=../../../etc/passwd')"

#log and old versions
echo "== history =="
echo "version two" >"$TMP/tower/work/notes.txt"
tower push notes.txt >/dev/null
laptop pull notes.txt >/dev/null
check "pull gets the newest version" "version two" "$(cat "$TMP/laptop/work/notes.txt")"

history=$(tower log notes.txt)
lines=$(echo "$history" | grep -c " notes.txt$")
check "log shows both versions of notes.txt" "2" "$lines"

old_hash=$(echo "$history" | head -1 | awk '{print $4}') # 4th column = short hash
laptop pull notes.txt old.txt --version "$old_hash" >/dev/null
check "pull an old version by its short hash" "version one" "$(cat "$TMP/laptop/work/old.txt" 2>&1)"
check "made-up hash is rejected" "0 versions" "$(laptop pull notes.txt x.txt --version ffffffff)"

#remove file
echo "== rm =="
check "rm removes a file" "removed school/notes.txt" "$(tower rm school/notes.txt)"
if tower ls | grep -q "school/notes.txt"; then
  fail "file is gone from ls after rm" "still listed"
else
  pass "file is gone from ls after rm"
fi
check "rm shows up in the history" "delete" "$(tower log school/notes.txt)"
check "rm of a missing file explains why" "404" "$(tower rm nope.txt)"

#send and inbox
echo "== send and inbox =="
echo "for the laptop" >"$TMP/tower/work/gift.txt"
check "send to another device" "inbox/laptop/gift.txt" "$(tower send gift.txt laptop)"
check "tower's own inbox is empty" "inbox is empty" "$(tower inbox)"
check "laptop sees it, and who sent it" "from tower" "$(laptop inbox)"
laptop inbox --get >/dev/null
same "inbox --get downloads to ~/Inbox" "$TMP/tower/work/gift.txt" "$TMP/laptop/Inbox/gift.txt"
check "inbox is empty after --get" "inbox is empty" "$(laptop inbox)"

#setup
echo "== setup =="
# A third fake device, "phone", that starts with NO config file.
mkdir -p "$TMP/phone/work"
phone() { (cd "$TMP/phone/work" && java -Duser.home="$TMP/phone" -cp "$TMP/classes" Client "$@" 2>&1); }
PHONE_CONFIG="$TMP/phone/.config/filecove/config.properties"
URL="http://127.0.0.1:$PORT"

check "commands need a config first" "no config file" "$(phone ls)"
check "setup with a wrong token fails" "401" "$(phone setup "$URL" wrong-token phone)"
if [ -e "$PHONE_CONFIG" ]; then
  fail "a failed setup saves nothing" "a config file was written anyway"
else
  pass "a failed setup saves nothing"
fi
check "setup refuses a bad device name" "may only use" "$(phone setup "$URL" "$TOKEN" 'my phone')"
check "setup refuses an address without http://" "must start with http://" "$(phone setup "127.0.0.1:$PORT" "$TOKEN" phone)"
check "setup works with the right token" "saved" "$(phone setup "$URL/" "$TOKEN" phone)"
check "the new config works" "notes.txt" "$(phone ls)"
check "the config is private (600)" "600" "$(stat -c %a "$PHONE_CONFIG")"
check "the trailing / was dropped" "server=$URL" "$(head -1 "$PHONE_CONFIG")"
check "setup won't overwrite without --force" "already exists" "$(phone setup "$URL" "$TOKEN" phone)"
check "setup --force replaces it" "for device phone2" "$(phone setup "$URL" "$TOKEN" phone2 --force)"
check "the new device name was saved" "device=phone2" "$(cat "$PHONE_CONFIG")"

#install-script.sh and cove
echo "== install =="
# Install into a throwaway folder inside $TMP, never your real ~/.local.
jar --create --file "$TMP/filecove.jar" -C "$TMP/classes" .
out=$(sh "$DIR/install-client.sh" "$TMP/filecove.jar" "$TMP/prefix" 2>&1)
check "install-client.sh reports success" "installed" "$out"
if [ -x "$TMP/prefix/bin/cove" ]; then
  pass "cove is installed and executable"
else
  fail "cove is installed and executable" "missing, or no execute permission"
fi
same "the jar is copied next to it" "$TMP/filecove.jar" "$TMP/prefix/share/filecove/filecove.jar"
check "cove runs the client" "usage:" "$(PATH="$TMP/prefix/bin:$PATH" cove --help 2>&1)"
check "cove works from another folder" "usage:" "$(cd / && PATH="$TMP/prefix/bin:$PATH" cove --help 2>&1)"
# JAVA_TOOL_OPTIONS hands -Duser.home to the java that cove starts, so it
# reads the fake tower's config instead of your real one.
check "cove talks to the server" "notes.txt" \
  "$(JAVA_TOOL_OPTIONS="-Duser.home=$TMP/tower" PATH="$TMP/prefix/bin:$PATH" cove ls 2>&1)"
check "cove passes names with spaces intact" "pulled" \
  "$(cd "$TMP/tower/work" && JAVA_TOOL_OPTIONS="-Duser.home=$TMP/tower" PATH="$TMP/prefix/bin:$PATH" cove pull "my notes.txt" "copy of my notes.txt" 2>&1)"
check "install-client.sh refuses a missing jar" "no such file" "$(sh "$DIR/install-client.sh" "$TMP/nope.jar" "$TMP/prefix2" 2>&1)"

#folder view ls
echo "== folders =="
echo "a" >"$TMP/tower/work/a.txt"
tower push a.txt docs/ >/dev/null
tower push a.txt docs/sub/b.txt >/dev/null
tower push a.txt docs/sub/c.txt >/dev/null
tower push a.txt solo/only.txt >/dev/null

top=$(tower ls)
check "ls shows a folder at the top level" "docs/" "$top"
check "ls counts the files inside a folder" "3 files" "$top"
check "a folder with one file says 'file'" "1 file," "$top"
check "ls still shows top-level files" "notes.txt" "$top"
if echo "$top" | grep -q "docs/sub/b.txt"; then
  fail "ls hides files inside folders" "docs/sub/b.txt was listed at the top level"
else
  pass "ls hides files inside folders"
fi

inside=$(tower ls docs)
check "ls FOLDER works without a trailing /" "docs/a.txt" "$inside"
check "ls FOLDER shows its subfolders" "docs/sub/" "$inside"
check "the subfolder count is right" "2 files" "$inside"
check "ls --all lists every file" "docs/sub/b.txt" "$(tower ls --all)"
check "ls of an empty folder says so" "no files in nothing/" "$(tower ls nothing)"

#keep bottom
echo "== server down =="
kill "$SERVER_PID" 2>/dev/null
wait "$SERVER_PID" 2>/dev/null
SERVER_PID=""
check "client explains when the server is down" "can't reach" "$(tower ls)" # [slice 2]

echo
echo "== $PASS passed, $FAIL failed =="
if [ "$FAIL" -gt 0 ]; then
  echo
  echo "Last lines of the server's output (Java errors and stack traces show up here):"
  tail -n 25 "$TMP/server.log"
  exit 1
fi
