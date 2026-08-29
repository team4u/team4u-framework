#!/usr/bin/env bash
set -euo pipefail

# Absolute performance claim gate. The claim language mixes CJK quantifier
# words (零/无/0) with ASCII keywords, and byte-oriented grep semantics change
# with the invoking locale, so the matching itself lives in the Java 8 helper
# scripts/PerformanceClaimScanner.java: UTF-8 decoding, locale-independent
# java.util.regex, subclause splitting at Chinese/ASCII punctuation, and the
# allowlist for approved mechanisms (无锁 alone, 无 BigDecimal, 无反射/无正则
# alone, zero get calls, low overhead, gc.alloc metrics, TimeUnit.NANOSECONDS).
# This wrapper compiles the helper, runs its built-in --self-test corpus first
# (the claim patterns must pass their own tests before scanning anything),
# then runs it over the repository root, and retains the raw-evidence checks
# that do not involve claim wording.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

javac -source 8 -target 8 -Xlint:-options -d "$WORK" \
  "$ROOT/scripts/PerformanceClaimScanner.java"
java -cp "$WORK" PerformanceClaimScanner --self-test
java -cp "$WORK" PerformanceClaimScanner "$ROOT"

FAILED=0

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
