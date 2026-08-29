#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM="$ROOT/pom.xml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

EXPECTED="$WORK/expected.txt"
cat >"$EXPECTED" <<'EOF'
team4u-base
team4u-base-jdbc
team4u-bean
team4u-bean-spring
team4u-config-core
team4u-config-db
team4u-config-proxy
team4u-config-spring
team4u-config-test
team4u-criterion
team4u-kv-core
team4u-kv-lifecycle
team4u-kv-lock
team4u-kv-retryable
team4u-kv-space
team4u-kv-store-jdbc
team4u-kv-store-redis
team4u-kv-test
team4u-lease-core
team4u-lease-jdbc
team4u-lease-memory
team4u-lease-test
team4u-log-core
team4u-log-governance
team4u-mask
team4u-mask-config
team4u-mask-jackson
team4u-policy
team4u-proxy
team4u-retry-config
team4u-retry-core
team4u-retry-lease-runtime
team4u-retry-managed
team4u-retry-proxy
team4u-retry-spring
team4u-router
team4u-router-proxy
team4u-serializer-jackson
team4u-serializer-json
team4u-translator
EOF

mkdir -p "$WORK/classes"
javac -d "$WORK/classes" "$ROOT/scripts/ReleasePomList.java"

fail() {
  echo "release contract failure: $*" >&2
  exit 1
}

compare_structure() {
  local label="$1"
  local actual="$2"
  local duplicate
  duplicate="$(sort "$actual" | uniq -d)"
  if [[ -n "$duplicate" ]]; then
    fail "duplicated $label entries: $(echo "$duplicate" | tr '\n' ' ')"
  fi
  if ! diff -u "$EXPECTED" "$actual" >&2; then
    fail "$label does not match the independent 40-leaf manifest"
  fi
}

java -cp "$WORK/classes" org.team4u.release.ReleasePomList modules "$POM" >"$WORK/module-paths.txt"
sed 's|.*/||' "$WORK/module-paths.txt" | LC_ALL=C sort >"$WORK/modules.txt"
compare_structure "root direct modules" "$WORK/modules.txt"

java -cp "$WORK/classes" org.team4u.release.ReleasePomList managed "$POM" >"$WORK/managed.txt"
compare_structure "root dependencyManagement com.team4u leaves" "$WORK/managed.txt"

: >"$WORK/artifacts.txt"
while IFS= read -r modulePath; do
  module_pom="$ROOT/$modulePath/pom.xml"
  [[ -f "$module_pom" ]] || fail "missing module POM for $modulePath: $module_pom"
  java -cp "$WORK/classes" org.team4u.release.ReleasePomList artifacts "$module_pom" >>"$WORK/artifacts.txt"
done <"$WORK/module-paths.txt"
LC_ALL=C sort -o "$WORK/artifacts.txt" "$WORK/artifacts.txt"
compare_structure "module POM artifact IDs" "$WORK/artifacts.txt"

if grep -qx 'team4u-framework' "$WORK/modules.txt" "$WORK/managed.txt"; then
  fail "the root BOM team4u-framework must not list itself as a released leaf"
fi
if grep -qx 'team4u-log' "$WORK/modules.txt" "$WORK/managed.txt" "$WORK/artifacts.txt"; then
  fail "legacy monolith team4u-log must remain absent"
fi
if grep -qx 'benchmarks' "$WORK/modules.txt"; then
  fail "benchmarks must stay outside the published leaf manifest"
fi

# Every published leaf must have its current version installed before runtime
# dependency inspection. The caller normally runs Maven install first. The leaf
# loop is derived from EXPECTED, not from the POM structure checked above.
LEAVES=()
while IFS= read -r leaf; do
  [[ -n "$leaf" ]] && LEAVES+=("$leaf")
done <"$EXPECTED"
JACKSON_OWNERS=(
  team4u-log-governance
  team4u-mask-jackson
  team4u-retry-lease-runtime
  team4u-serializer-jackson
)
if [[ "${#JACKSON_OWNERS[@]}" -ne 4 ]]; then
  fail "provider/Jackson ownership must allow exactly four modules"
fi
LEAK_INCLUDES='com.team4u:*,com.fasterxml.jackson*'
LEAKS=0

for leaf in "${LEAVES[@]}"; do
  module_dir="$ROOT/$leaf"
  [[ -f "$module_dir/pom.xml" ]] || module_dir="$(dirname "$(find "$ROOT" -name target -prune -o -name .worktrees -prune -o -path "*/$leaf/pom.xml" -print -quit 2>/dev/null)")"
  tree="$WORK/$leaf.tree"
  log="$WORK/$leaf.dependency.log"
  if ! mvn -q -f "$module_dir/pom.xml" dependency:tree -Dscope=runtime \
    -Dincludes="$LEAK_INCLUDES" -DoutputFile="$tree" >"$log" 2>&1; then
    cat "$log" >&2
    fail "cannot inspect runtime dependency tree for $leaf"
  fi
  if [[ ! -s "$tree" ]] || ! grep -q "com[.]team4u:$leaf[::]" "$tree"; then
    cat "$tree" >&2
    fail "dependency tree gate is empty or missing its owner for $leaf"
  fi

  hits="$WORK/$leaf.hits"
  : >"$hits"
  grep -E 'com[.]team4u:team4u-serializer-jackson|com[.]fasterxml[.]jackson' "$tree" >"$hits" || true
  owner=no
  for allowed in "${JACKSON_OWNERS[@]}"; do
    [[ "$leaf" == "$allowed" ]] && owner=yes
  done
  if [[ -s "$hits" && "$owner" == no ]]; then
    LEAKS=1
    echo "Jackson/provider leakage: module=$leaf" >&2
    sed 's/^/  artifact=/g' "$hits" >&2
  fi
done

# A nonempty owner tree without a provider/Jackson row would let the leakage
# check pass without exercising ownership semantics. Governance is the split
# representative whose provider/Jackson line must be present.
if ! grep -Eq 'com[.]team4u:team4u-serializer-jackson|com[.]fasterxml[.]jackson' \
  "$WORK/team4u-log-governance.tree"; then
  fail "team4u-log-governance dependency tree does not contain its expected provider/Jackson row"
fi

if [[ "$LEAKS" -ne 0 ]]; then
  fail "non-owner modules expose serializer-jackson or com.fasterxml.jackson at compile/runtime scope"
fi

check_shape() {
  local leaf="$1"
  local expected="$2"
  local actual
  actual="$(grep -E '^[+\\]- com[.]team4u:' "$WORK/$leaf.tree" | \
    sed -E 's/^[+\\]- com[.]team4u:([^:]+):.*/\1/' | LC_ALL=C sort)"
  if [[ "$actual" != "$expected" ]]; then
    echo "direct Team4u dependency shape mismatch for $leaf" >&2
    echo "expected: $(echo "$expected" | tr '\n' ' ')" >&2
    echo "actual:   $(echo "$actual" | tr '\n' ' ')" >&2
    exit 1
  fi
}

check_shape team4u-base-jdbc $'team4u-base'
check_shape team4u-criterion $'team4u-base\nteam4u-policy'
check_shape team4u-proxy $'team4u-base'
check_shape team4u-serializer-json $'team4u-base\nteam4u-policy'
check_shape team4u-serializer-jackson $'team4u-serializer-json'
check_shape team4u-config-core $'team4u-base\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-config-proxy $'team4u-config-core\nteam4u-proxy'
check_shape team4u-config-spring $'team4u-config-core\nteam4u-policy'
check_shape team4u-bean-spring $'team4u-bean'
check_shape team4u-lease-core $'team4u-base'
check_shape team4u-retry-core $'team4u-base\nteam4u-criterion\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-retry-managed $'team4u-retry-core\nteam4u-serializer-json'
check_shape team4u-retry-config $'team4u-config-core\nteam4u-retry-core'
check_shape team4u-kv-core $'team4u-base'
check_shape team4u-kv-space $'team4u-kv-core\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-router $'team4u-base\nteam4u-config-core\nteam4u-criterion\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-router-proxy $'team4u-bean\nteam4u-proxy\nteam4u-router'
check_shape team4u-mask $'team4u-base\nteam4u-policy'
check_shape team4u-mask-jackson $'team4u-mask'
check_shape team4u-mask-config $'team4u-config-core\nteam4u-mask\nteam4u-serializer-json'
check_shape team4u-log-core $'team4u-base\nteam4u-policy'
check_shape team4u-log-governance $'team4u-config-core\nteam4u-criterion\nteam4u-log-core\nteam4u-mask\nteam4u-mask-config\nteam4u-mask-jackson\nteam4u-proxy\nteam4u-serializer-jackson\nteam4u-serializer-json'
EFFECTIVE="$WORK/effective-pom.xml"
if ! mvn -q -f "$POM" help:effective-pom -Doutput="$EFFECTIVE" >"$WORK/effective.log" 2>&1; then
  cat "$WORK/effective.log" >&2
  fail "cannot generate effective root POM"
fi
if grep -q 'maven.aliyun.com' "$EFFECTIVE"; then
  fail "effective root POM still resolves maven.aliyun.com"
fi

echo "release contracts: 40/40 leaves, Jackson owners, and effective POM repository verified"
