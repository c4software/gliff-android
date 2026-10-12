#!/bin/sh
# A stand-in for gliff-server that loops an H.264 sample: point a client at it
# with --server-bin (probe) or the "server path" of a machine (app).
# Build it first: ./gradlew :transport:toolsClasspath && scripts/make-sample.sh
here="$(cd "$(dirname "$0")/.." && pwd)"
: "${GLIFF_FAKE_SAMPLE:=$here/transport/build/tools/sample.h264}"
export GLIFF_FAKE_SAMPLE
exec "${JAVA:-java}" -cp "$(cat "$here/transport/build/tools/classpath.txt")" \
  io.github.c4software.gliff.transport.FakeServerKt "$@"
