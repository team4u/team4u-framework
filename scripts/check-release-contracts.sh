#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM="$ROOT/pom.xml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Independent 48-leaf manifest. Every entry below reflects the 2D
# modules/<family>/<variant> layout with bare artifactIds for core modules.
EXPECTED="$WORK/expected.txt"
cat >"$EXPECTED" <<'EOF_EXPECTED'
team4u-base
team4u-base-jdbc
team4u-bean
team4u-bean-spring
team4u-config
team4u-config-db
team4u-config-proxy
team4u-config-spring
team4u-config-test
team4u-criterion
team4u-id
team4u-kv
team4u-kv-lifecycle
team4u-kv-lock
team4u-kv-retryable
team4u-kv-space
team4u-kv-store-jdbc
team4u-kv-store-redis
team4u-kv-test
team4u-lease
team4u-lease-jdbc
team4u-lease-memory
team4u-lease-test
team4u-log
team4u-log-governance
team4u-mask
team4u-mask-config
team4u-mask-jackson
team4u-policy
team4u-proxy
team4u-proxy-spring
team4u-ratelimiter
team4u-ratelimiter-proxy
team4u-ratelimiter-spring
team4u-retry
team4u-retry-config
team4u-retry-lease-runtime
team4u-retry-managed
team4u-retry-proxy
team4u-retry-spring
team4u-router
team4u-router-proxy
team4u-serializer-jackson
team4u-serializer-json
team4u-singleflight
team4u-singleflight-proxy
team4u-singleflight-spring
team4u-translator
EOF_EXPECTED

EXPECTED_MODULES="$WORK/expected-modules.txt"
cat >"$EXPECTED_MODULES" <<'EOF_MODULES'
modules/base/core
modules/base/jdbc
modules/bean/core
modules/bean/spring
modules/config/core
modules/config/db
modules/config/proxy
modules/config/spring
modules/config/test
modules/criterion/core
modules/id/core
modules/kv/core
modules/kv/lifecycle
modules/kv/lock
modules/kv/retryable
modules/kv/space
modules/kv/store-jdbc
modules/kv/store-redis
modules/kv/test
modules/lease/core
modules/lease/jdbc
modules/lease/memory
modules/lease/test
modules/log/core
modules/log/governance
modules/mask/config
modules/mask/core
modules/mask/jackson
modules/policy/core
modules/proxy/core
modules/proxy/spring
modules/ratelimiter/core
modules/ratelimiter/proxy
modules/ratelimiter/spring
modules/retry/config
modules/retry/core
modules/retry/lease-runtime
modules/retry/managed
modules/retry/proxy
modules/retry/spring
modules/router/core
modules/router/proxy
modules/serializer/jackson
modules/serializer/json
modules/singleflight/core
modules/singleflight/proxy
modules/singleflight/spring
modules/translator/core
EOF_MODULES

mkdir -p "$WORK/classes"
javac -d "$WORK/classes" "$ROOT/scripts/ReleasePomList.java"

fail() {
  echo "release contract failure: $*" >&2
  exit 1
}

compare_structure() {
  local expected_file="$1"
  local label="$2"
  local actual="$3"
  local duplicate
  duplicate="$(sort "$actual" | uniq -d)"
  if [[ -n "$duplicate" ]]; then
    fail "duplicated $label entries: $(echo "$duplicate" | tr '
' ' ')"
  fi
  if ! diff -u "$expected_file" "$actual" >&2; then
    fail "$label does not match the independent 48-leaf manifest"
  fi
}

java -cp "$WORK/classes" org.team4u.release.ReleasePomList modules "$POM" >"$WORK/module-paths.txt"
LC_ALL=C sort "$WORK/module-paths.txt" >"$WORK/modules.txt"
compare_structure "$EXPECTED_MODULES" "root direct modules" "$WORK/modules.txt"

java -cp "$WORK/classes" org.team4u.release.ReleasePomList managed "$POM" >"$WORK/managed.txt"
compare_structure "$EXPECTED" "root dependencyManagement com.team4u leaves" "$WORK/managed.txt"

: >"$WORK/artifacts.txt"
while IFS= read -r modulePath; do
  module_pom="$ROOT/$modulePath/pom.xml"
  [[ -f "$module_pom" ]] || fail "missing module POM for $modulePath: $module_pom"
  java -cp "$WORK/classes" org.team4u.release.ReleasePomList artifacts "$module_pom" >>"$WORK/artifacts.txt"
done <"$WORK/module-paths.txt"
LC_ALL=C sort -o "$WORK/artifacts.txt" "$WORK/artifacts.txt"
compare_structure "$EXPECTED" "module POM artifact IDs" "$WORK/artifacts.txt"

if grep -qx 'team4u-framework' "$WORK/managed.txt"; then
  fail "the root BOM team4u-framework must not list itself as a released leaf"
fi
if grep -Ex 'team4u-(config|kv|lease|log|ratelimiter|retry|singleflight)-core' "$WORK/managed.txt" "$WORK/artifacts.txt"; then
  fail "old -core suffix must remain absent from artifact IDs"
fi
if grep -qx 'benchmarks' "$WORK/module-paths.txt"; then
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
  team4u-singleflight
)
if [[ "${#JACKSON_OWNERS[@]}" -ne 5 ]]; then
  fail "provider/Jackson ownership must allow exactly five modules"
fi
# singleflight owns a direct nonoptional jackson-databind compile edge
# (durable-schema exemption). That edge necessarily flows transitively to its
# adapters; they inherit the databind runtime artifact but never the provider.
# Heirs may carry com.fasterxml.jackson only, never team4u-serializer-jackson.
DATABIND_HEIRS=(
  team4u-singleflight-proxy
  team4u-singleflight-spring
)
LEAK_INCLUDES='com.team4u:*,com.fasterxml.jackson*'
LEAKS=0

declare -A MODULE_POMS
while IFS= read -r modulePath; do
  module_pom="$ROOT/$modulePath/pom.xml"
  art="$(java -cp "$WORK/classes" org.team4u.release.ReleasePomList artifacts "$module_pom")"
  MODULE_POMS["$art"]="$module_pom"
done <"$WORK/module-paths.txt"

for leaf in "${LEAVES[@]}"; do
  module_pom="${MODULE_POMS["$leaf"]:-}"
  [[ -n "$module_pom" && -f "$module_pom" ]] || fail "missing module POM for $leaf"
  module_dir="$(dirname "$module_pom")"
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
  heir=no
  for allowed in "${DATABIND_HEIRS[@]}"; do
    [[ "$leaf" == "$allowed" ]] && heir=yes
  done
  if [[ "$owner" == no && -s "$hits" ]]; then
    if [[ "$heir" == yes ]]; then
      # Heirs may only carry raw Jackson inherited from singleflight's
      # databind edge; the provider artifact remains forbidden.
      if grep -q 'com[.]team4u:team4u-serializer-jackson' "$hits"; then
        LEAKS=1
        echo "Jackson provider leakage: module=$leaf" >&2
        sed 's/^/  artifact=/g' "$hits" >&2
      fi
    else
      LEAKS=1
      echo "Jackson/provider leakage: module=$leaf" >&2
      sed 's/^/  artifact=/g' "$hits" >&2
    fi
  fi
done

# A nonempty owner tree without a provider/Jackson row would let the leakage
# check pass without exercising ownership semantics. Each owner below has a
# distinct ownership shape that must be present in its filtered runtime tree:
#  - serializer-jackson / mask-jackson / log-governance / retry-lease-runtime
#    carry the provider artifact team4u-serializer-jackson,
#  - singleflight owns only a direct jackson-databind edge (durable-schema
#    exemption) and must NOT carry team4u-serializer-jackson.
# Heirs must carry the inherited databind edge but never the provider.
for owner_leaf in \
    team4u-serializer-jackson \
    team4u-mask-jackson \
    team4u-log-governance \
    team4u-retry-lease-runtime; do
  if ! grep -q 'com[.]team4u:team4u-serializer-jackson' "$WORK/$owner_leaf.tree"; then
    fail "$owner_leaf dependency tree does not contain its expected provider row team4u-serializer-jackson"
  fi
done
if ! grep -q 'com[.]fasterxml[.]jackson[.]core:jackson-databind' "$WORK/team4u-singleflight.tree"; then
  fail "team4u-singleflight dependency tree does not contain its expected direct jackson-databind durable-schema edge"
fi
if grep -q 'com[.]team4u:team4u-serializer-jackson' "$WORK/team4u-singleflight.tree"; then
  fail "team4u-singleflight must not expose the serializer provider at runtime (databind-only durable-schema exemption)"
fi
for heir_leaf in "${DATABIND_HEIRS[@]}"; do
  if grep -q 'com[.]team4u:team4u-serializer-jackson' "$WORK/$heir_leaf.tree"; then
    fail "$heir_leaf must not expose the serializer provider at runtime"
  fi
done

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

# 30 representative direct Team4u dependency shapes.
check_shape team4u-base-jdbc $'team4u-base'
check_shape team4u-criterion $'team4u-base\nteam4u-policy'
check_shape team4u-proxy $'team4u-base'
check_shape team4u-proxy-spring $'team4u-proxy'
check_shape team4u-serializer-json $'team4u-base\nteam4u-policy'
check_shape team4u-serializer-jackson $'team4u-serializer-json'
check_shape team4u-config $'team4u-base\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-config-proxy $'team4u-config\nteam4u-proxy'
check_shape team4u-config-spring $'team4u-config\nteam4u-policy'
check_shape team4u-bean-spring $'team4u-bean'
check_shape team4u-lease $'team4u-base'
check_shape team4u-retry $'team4u-base\nteam4u-criterion\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-retry-managed $'team4u-retry\nteam4u-serializer-json'
check_shape team4u-retry-config $'team4u-config\nteam4u-retry'
check_shape team4u-kv $'team4u-base'
check_shape team4u-kv-space $'team4u-kv\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-router $'team4u-base\nteam4u-config\nteam4u-criterion\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-router-proxy $'team4u-bean\nteam4u-proxy\nteam4u-router'
check_shape team4u-mask $'team4u-base\nteam4u-policy'
check_shape team4u-mask-jackson $'team4u-mask\nteam4u-serializer-jackson'
check_shape team4u-mask-config $'team4u-config\nteam4u-mask\nteam4u-serializer-json'
check_shape team4u-log $'team4u-base\nteam4u-policy'
check_shape team4u-log-governance $'team4u-config\nteam4u-criterion\nteam4u-log\nteam4u-mask\nteam4u-mask-config\nteam4u-mask-jackson\nteam4u-proxy\nteam4u-serializer-jackson\nteam4u-serializer-json'
check_shape team4u-id $'team4u-base\nteam4u-config\nteam4u-kv\nteam4u-kv-space\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-ratelimiter $'team4u-base\nteam4u-config\nteam4u-kv\nteam4u-kv-space\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-ratelimiter-proxy $'team4u-proxy\nteam4u-ratelimiter'
check_shape team4u-ratelimiter-spring $'team4u-proxy-spring\nteam4u-ratelimiter-proxy'
check_shape team4u-singleflight $'team4u-base\nteam4u-config\nteam4u-criterion\nteam4u-kv\nteam4u-kv-lock\nteam4u-kv-space\nteam4u-policy\nteam4u-serializer-json'
check_shape team4u-singleflight-proxy $'team4u-proxy\nteam4u-singleflight'
check_shape team4u-singleflight-spring $'team4u-proxy-spring\nteam4u-singleflight-proxy'
EFFECTIVE="$WORK/effective-pom.xml"
if ! mvn -q -f "$POM" help:effective-pom -Doutput="$EFFECTIVE" >"$WORK/effective.log" 2>&1; then
  cat "$WORK/effective.log" >&2
  fail "cannot generate effective root POM"
fi
if grep -q 'maven.aliyun.com' "$EFFECTIVE"; then
  fail "effective root POM still resolves maven.aliyun.com"
fi

echo "release contracts: 48/48 leaves, Jackson owners, and effective POM repository verified"
