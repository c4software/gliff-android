#!/bin/sh
# Encode the sample the stand-in server loops: 1280x720, 30 fps, no B-frames,
# SPS/PPS in front of every keyframe, as gliff-server emits them.
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$here/transport/build/tools"
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=${1:-1280x720}:rate=30" -t 6 \
  -c:v libx264 -profile:v high -preset veryfast -tune zerolatency -bf 0 -g 30 \
  -x264-params repeat-headers=1:slices=1 -pix_fmt yuv420p -f h264 \
  "$here/transport/build/tools/sample.h264"
echo "$here/transport/build/tools/sample.h264"
