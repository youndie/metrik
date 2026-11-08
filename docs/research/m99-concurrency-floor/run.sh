#!/usr/bin/env bash
# M-99: does a minimal native Ktor CIO server grow the same concurrency floor as metrik, and does
# MALLOC_ARENA_MAX=2 cut it? Linux aarch64 in Docker Desktop; subject on cpus 0-1, load on 2-3.
#
# Pre-registered (08.11.2026, before the first number):
#   Q1 "not metrik's": minimal's floor after the 200-burst grows >= 2x over its idle AND by at least
#      half of metrik's growth (floor200 - idle), median of 5 → green; < 1.3x → red; between → grey.
#   Q2 "arenas": MALLOC_ARENA_MAX=2 lowers metrik's floor after the 200-burst by >= 25 % (median of 5)
#      with the same sign in >= 4 of 5 pairs → green; same sign < 4/5 or < 10 % → red; else grey.
#   Positive control: anon must rise during a burst at all (peak > idle + 5 MiB), or the run is void.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/results.tsv
REPEATS=${REPEATS:-5}
REST=${REST:-45}
NET=m99net
docker network inspect $NET >/dev/null 2>&1 || docker network create $NET >/dev/null

anon() { docker exec "$1" sh -c 'awk "/^anon /{print int(\$2/1048576)}" /sys/fs/cgroup/memory.stat'; }
threads() { docker exec "$1" sh -c 'awk "/^Threads/{print \$2}" /proc/1/status'; }

burst() { # name host path concurrency → prints peak anon during the burst
  local name=$1 url="http://$2:8080$3" c=$4 peak=0 a
  docker run --rm --network $NET --cpuset-cpus 2,3 httpd:2.4-alpine \
    ab -q -k -n $((c * 40)) -c "$c" "$url" >"$HERE/ab-last.txt" 2>&1 &
  local pid=$!
  while kill -0 $pid 2>/dev/null; do
    a=$(anon "$name" 2>/dev/null || echo 0); [ "${a:-0}" -gt "$peak" ] && peak=$a; sleep 0.3
  done
  wait $pid
  grep -q 'Failed requests:        0' "$HERE/ab-last.txt" || echo "WARN failed requests: $(grep -E 'Failed|Non-2xx' "$HERE/ab-last.txt" | tr -s ' ' | tr '\n' ' ')" >&2
  echo "$peak"
}

one() { # arm rep
  local arm=$1 rep=$2 name="m99-$1" bin path env=()
  case $arm in
    minimal*) bin=$HERE/minimal.kexe; path=/health ;;
    metrik*) bin=$HERE/metrik.kexe; path=/version
             env=(-e METRIK_INGEST_KEY=dev-key -e METRIK_DB_PATH=/tmp/metrik.db) ;;
  esac
  case $arm in *-arena2) env+=(-e MALLOC_ARENA_MAX=2) ;; esac
  docker rm -f "$name" >/dev/null 2>&1
  docker run -d --name "$name" --network $NET --cpuset-cpus 0,1 --memory 1g ${env[@]+"${env[@]}"} \
    -v "$bin:/app/server:ro" debian:trixie-slim /app/server >/dev/null
  sleep 6
  local idle; idle=$(anon "$name")
  local p50 f50 p200 f200 t200
  p50=$(burst "$name" "$name" "$path" 50); sleep "$REST"; f50=$(anon "$name")
  p200=$(burst "$name" "$name" "$path" 200); t200=$(threads "$name"); sleep "$REST"; f200=$(anon "$name")
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$arm" "$rep" "$idle" "$p50" "$f50" "$p200" "$f200" "$t200" | tee -a "$OUT"
  docker rm -f "$name" >/dev/null
}

[ -f "$OUT" ] || printf 'arm\trep\tidle\tpeak50\tfloor50\tpeak200\tfloor200\tthreads200\n' >"$OUT"
ARMS=(minimal metrik minimal-arena2 metrik-arena2)
for rep in $(seq 1 "$REPEATS"); do
  # rotate the order every repeat so no arm always runs first on a warm VM
  for k in 0 1 2 3; do one "${ARMS[$(((k + rep) % 4))]}" "$rep"; done
done
echo DONE
