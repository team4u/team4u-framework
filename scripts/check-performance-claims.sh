#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILED=0

# Case-insensitive blanket performance claims, Chinese plus English variants:
#   0 GC / 0 GCC / zero GC / GC-free / no GC / 无 GC / 零 GC
#   零对象 / 零对象创建 / 0 对象 / zero object creation / object-creation-free
#   零分配 / zero allocation / no allocation / allocation-free / alloc-free
#   零开销 / zero overhead / no overhead / overhead-free
#   GC-free / allocation-free / object-creation-free / overhead-free
#   纳秒级 / nanosecond-level (any spacing or separator)
#   杜绝频繁 GC
# `0 GC` and `0 GCC` require a non-alphanumeric boundary before the digit and a
# non-alphanumeric boundary after the keyword, so counts like `10 GC`, version
# strings like `1.0 GC`, identifiers like `x0GCCy`, and profiler metrics like
# `gc.alloc.rate.norm` do not trip the rule. `TimeUnit.NANOSECONDS` is exempt
# via the `nanosecond-level` phrasing requirement (no `-level` suffix matches).
# Chinese exemptions: `低开销`/`无锁`/`无 BigDecimal` do not match because the
# banned patterns are anchored to the exact claim words (零开销/无 GC/零分配...).
pattern='(^|[^.[:alnum:]])0[[:space:]]*(GC|GCC)([^[:alnum:]]|$)'\
'|(zero|no)[[:space:]]*-?[[:space:]]*GC([^[:alnum:]]|$)'\
'|GC[-_ ]?free'\
'|(无|零)[[:space:]]*GC([^[:alnum:]]|$)'\
'|(无|零)[[:space:]]*分配'\
'|(零|0)[[:space:]]*对象'\
'|object([-_ ]?creation)?[[:space:]]*-?[[:space:]]*free'\
'|(zero|no)[[:space:]]*-?[[:space:]]*object[[:space:]]*-?[[:space:]]*creation'\
'|(zero|no)[[:space:]]*-?[[:space:]]*alloc'\
'|alloc(ation)?[[:space:]]*-?[[:space:]]*free'\
'|(零|0)[[:space:]]*开销'\
'|(zero|no)[[:space:]]*-?[[:space:]]*overhead'\
'|overhead[[:space:]]*-?[[:space:]]*free'\
'|纳秒级|nanosecond[[:space:]_-]*level'\
'|杜绝频繁[[:space:]]*GC'

fail_scan() {
  local label="$1"
  shift
  local hits
  hits="$(grep -RIniE "$pattern" "$@" 2>/dev/null || true)"
  if [[ -n "$hits" ]]; then
    echo "unsupported blanket performance claim ($label):" >&2
    echo "$hits" | while IFS= read -r match; do
      echo "  $match" >&2
    done
    FAILED=1
  fi
}

# Active documentation and root README. Historical convergence plans/specs
# under docs/superpowers are excluded on purpose.
docs_args=(--include='*.md' --include='*.markdown' --exclude-dir=superpowers)
fail_scan 'docs' "$ROOT/README.md" "$ROOT/MIGRATION-1.0.md" "$ROOT/docs" "${docs_args[@]}"

# Production and benchmark Java sources (Javadoc and code) outside target/.
java_sources=()
while IFS= read -r src; do
  java_sources+=("$src")
done < <(find "$ROOT" -type d -name target -prune -o \
  -type f -name '*.java' -path '*/src/main/java/*' -print 2>/dev/null)
if [[ "${#java_sources[@]}" -gt 0 ]]; then
  fail_scan 'sources' "${java_sources[@]}"
else
  echo "performance claims gate: RED (no */src/main/java sources found to scan)" >&2
  exit 1
fi

readme="$ROOT/benchmarks/README.md"
for requirement in \
  'CriterionMatchBenchmark' \
  'RouterRouteBenchmark' \
  'KvTieredReadBenchmark' \
  'ProxyDelegateBenchmark' \
  '-prof gc' \
  'gc.alloc.rate.norm' \
  'environment'; do
  if ! grep -Fqi -- "$requirement" "$readme"; then
    echo "benchmark evidence documentation is missing: $requirement" >&2
    FAILED=1
  fi
done

# Raw JMH evidence must exist for every benchmark class before the gate can be
# green: one JSON and one text result file each, plus the environment record.
for class_name in \
  CriterionMatchBenchmark \
  RouterRouteBenchmark \
  KvTieredReadBenchmark \
  ProxyDelegateBenchmark; do
  source="$ROOT/benchmarks/src/main/java/com/team4u/bench/$class_name.java"
  if [[ ! -f "$source" ]]; then
    echo "missing JMH benchmark class: $source" >&2
    FAILED=1
  fi
  for ext in json txt; do
    result="$ROOT/benchmarks/results/$class_name.$ext"
    if [[ ! -s "$result" ]]; then
      echo "missing or empty JMH raw evidence: $result" >&2
      FAILED=1
    fi
  done
done
if [[ ! -s "$ROOT/benchmarks/results/environment.txt" ]]; then
  echo "missing or empty benchmark environment record: $ROOT/benchmarks/results/environment.txt" >&2
  FAILED=1
fi

if [[ "$FAILED" -ne 0 ]]; then
  echo "performance claims gate: RED" >&2
  exit 1
fi

echo "performance claims gate: GREEN"
