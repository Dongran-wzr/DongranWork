#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERSION=3.9.11
MAVEN="$ROOT/.tools/apache-maven-$VERSION/bin/mvn"
if [ ! -x "$MAVEN" ]; then
  mkdir -p "$ROOT/.tools"
  ARCHIVE="$ROOT/.tools/apache-maven-$VERSION-bin.tar.gz"
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz"
  curl --fail --location "$URL" --output "$ARCHIVE"
  EXPECTED=$(curl --fail --location "$URL.sha512")
  ACTUAL=$(shasum -a 512 "$ARCHIVE" | cut -d ' ' -f 1)
  [ "$ACTUAL" = "$EXPECTED" ] || { echo 'Maven checksum mismatch' >&2; exit 1; }
  tar -xzf "$ARCHIVE" -C "$ROOT/.tools"
fi
exec "$MAVEN" "$@"
