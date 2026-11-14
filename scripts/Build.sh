#compiles Server.java and Client.java into 1 .jar file

set -eu
cd "$(dirname "$0")/.."
rm -rf build
javac --release 17 -d build src/Server.java src/Client.java
jar --create --file filecove.jar -C build .
echo "Filecove.jar build complete"
