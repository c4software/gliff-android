#!/bin/sh
# Encodes sample.h264, the desktop the demonstration server loops, from
# pictures composed by the StoreShots test: 1280x720, 30 fps, no B-frames,
# SPS and PPS in front of every keyframe, as gliff-server emits them.
# Each picture stays half a second; a keyframe every second.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
(cd "$root" && GLIFF_STORE=1 ./gradlew -q :shared:desktopTest --tests '*StoreShots.demoFrames*')
ffmpeg -loglevel error -y -framerate 2 -i "$root/shared/build/demo-frames/%03d.png" \
  -vf fps=30 -c:v libx264 -profile:v high -preset slow -tune zerolatency -crf 20 -bf 0 -g 30 \
  -x264-params repeat-headers=1:slices=1:keyint=30:min-keyint=30:scenecut=0 -pix_fmt yuv420p -f h264 \
  "$here/sample.h264"
ls -l "$here/sample.h264"
