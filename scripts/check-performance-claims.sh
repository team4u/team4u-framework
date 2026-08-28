#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILED=0

pattern='0[[:space:]]*GC|零[[:space:]]*GC|零分配|纳秒级|杜绝频繁[[:space:]]*GC[[:space:]]*抖动'
while IFS= read -r match; do
  echo "unsupported blanket performance claim: $match" >&2
  FAILED=1
done < <(grep -RIniE "$pattern" "$ROOT/README.md" "$ROOT/docs" \
  --include='*.md' --include='*.markdown' \
  --exclude-dir=superpowers 2>/dev/null || true)

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
done

if [[ "$FAILED" -ne 0 ]]; then
  echo "performance claims gate: RED" >&2
  exit 1
fi

echo "performance claims gate: GREEN"
