#!/bin/sh
# install-client.sh - install the cove command for this user.
#
#   sh install-client.sh path/to/filecove.jar            -> ~/.local
#   sh install-client.sh path/to/filecove.jar "$PREFIX"  -> Termux
#   sudo sh install-client.sh path/to/filecove.jar /usr/local   -> every user
set -eu

jar=${1:?usage: sh install-client.sh path/to/filecove.jar [PREFIX]}
prefix=${2:-$HOME/.local}
[ -f "$jar" ] || {
  echo "no such file: $jar"
  exit 1
}
command -v java >/dev/null || {
  echo "java not found; install Java 17 or newer first"
  exit 1
}

mkdir -p "$prefix/bin" "$prefix/share/filecove"
install -m 644 "$jar" "$prefix/share/filecove/filecove.jar"
install -m 755 "$(dirname "$0")/cove" "$prefix/bin/cove"
echo "installed: $prefix/bin/cove"

# Is that bin folder on PATH? If not, typing "cove" won't find it.
case ":$PATH:" in
*":$prefix/bin:"*) echo "ready: try 'cove --help'" ;;
*) echo "note: $prefix/bin is not on your PATH yet; add it, then open a new terminal" ;;
esac
