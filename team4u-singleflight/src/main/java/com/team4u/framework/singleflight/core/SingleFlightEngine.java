package com.team4u.framework.singleflight.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team4u.framework.base.util.TextTemplate;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.lock.KvLock;
import com.team4u.framework.kv.lock.KvLockManager;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import com.team4u.framework.singleflight.api.SingleFlightConflictException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlightExecutionException;
import com.team4u.framework.singleflight.api.SingleFlightTimeoutException;
import com.team4u.framework.singleflight.config.ContentionPolicy;
import com.team4u.framework.singleflight.config.InvalidKeyPolicy;
import com.team4u.framework.singleflight.config.RuleMissingPolicy;
import com.team4u.framework.singleflight.config.SingleFlightRule;
import com.team4u.framework.singleflight.config.StoreFailurePolicy;
import com.team4u.framework.singleflight.policy.FallbackConverter;
import com.team4u.framework.singleflight.policy.KeyResolver;
import com.team4u.framework.singleflight.policy.SingleFlightCondition;
import com.team4u.framework.singleflight.store.SingleFlightStores;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Singleflight engine: one loader executor per key while competing callers finish
 * by WAIT, FAIL_FAST, or FALLBACK.
 *
 * @author jay.wu
 */
@Slf4j
public class SingleFlightEngine implements AutoCloseable {

    public static final String DEFAULT_CONFIG_PATTERN = "team4u.singleflight.*";
    public static final String GLOBAL_RULE_MISSING_KEY = "team4u.singleflight.on_rule_missing";
    public static final String LOCK_SPACE = "singleflight.lock";
    public static final String SESSION_SPACE = "singleflight.session";
    public static final String CACHE_SPACE = "singleflight.cache";

    private final ConfigManager configManager;
    private final KvStore defaultStore;
    private final KvStore defaultCoordinationStore;
    private final CasCapable defaultCas;
    private final Clock clock;
    private final FallbackConverter fallbackConverter = new FallbackConverter();
    private final ConfigDrivenRegistry<CompiledRule> rules;
    private final Map<String, KeyResolver> templates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KvLockManager> lockManagers = new ConcurrentHashMap<>();
    private final Map<String, String> activeStores = new ConcurrentHashMap<>();

    public SingleFlightEngine(ConfigManager configManager, KvStore defaultStore) {
        this(configManager, defaultStore, Clock.systemUTC());
    }

    public SingleFlightEngine(ConfigManager configManager, KvStore defaultStore, Clock clock) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.defaultStore = unwrapStore(Objects.requireNonNull(defaultStore, "defaultStore"));
        this.defaultCas = requireCas(this.defaultStore);
        this.defaultCoordinationStore = (KvStore) this.defaultCas;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rules = new ConfigDrivenRegistry<>(configManager, DEFAULT_CONFIG_PATTERN,
                this::compileRule);
    }

    public <T> T execute(SingleFlightExecution<T> execution) {
        if (execution.getPoint() == null || execution.getPoint().trim().isEmpty()) {
            throw new SingleFlightConfigException("Singleflight point must not be empty");
        }
        CompiledRule rule = rules.get(execution.getPoint());
        if (rule == null) {
            return onRuleMissing(execution);
        }
        if (!execution.getPoint().equals(rule.rule.getId())) {
            throw new SingleFlightConfigException("Singleflight rule id must match point"
                    + "|point=" + execution.getPoint() + "|id=" + rule.rule.getId());
        }
        if (!rule.rule.isEnabled()) {
            return load(execution);
        }
        validateCriterionVariables(rule.skipWhen, execution, "skipWhen");
        validateRuleForExecution(rule.rule, execution);
        if (rule.skipWhen != null && matchesSkip(rule, execution)) {
            return load(execution);
        }

        String key = renderKey(rule, execution);
        if (key == null) {
            return load(execution);
        }
        try {
            return executeWithKey(rule, execution, key);
        } catch (KvStoreException e) {
            return onStoreFailure(rule, execution, e);
        }
    }

    private <T> T onRuleMissing(SingleFlightExecution<T> execution) {
        RuleMissingPolicy policy = globalRuleMissingPolicy();
        if (policy == RuleMissingPolicy.ERROR) {
            throw new SingleFlightConfigException(
                    "Singleflight rule is missing|point=" + execution.getPoint());
        }
        log.warn("SingleflightEngine|ruleMissingPassThrough|point={}", execution.getPoint());
        return load(execution);
    }

    private RuleMissingPolicy globalRuleMissingPolicy() {
        String value = configManager.getString(GLOBAL_RULE_MISSING_KEY).orElse(null);
        if (value == null || value.trim().isEmpty()) {
            return RuleMissingPolicy.PASS_THROUGH;
        }
        try {
            return RuleMissingPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new SingleFlightConfigException(
                    "Invalid onRuleMissing policy|value=" + value, e);
        }
    }

    private <T> T executeWithKey(CompiledRule rule, SingleFlightExecution<T> execution,
                                 String key) {
        if (rule.rule.isCacheEnabled()) {
            KvRecord cached = rule.resultStore.get(cacheKey(key));
            if (cached != null) {
                return cast(decode(cached.getValue(), execution.getReturnType()));
            }
        }

        KvRecord record = rule.coordinationStore.get(sessionKey(key));
        if (record != null && SessionEnvelope.of(record.getValue()).isTerminal()) {
            return finishSession(SessionEnvelope.of(record.getValue()), execution);
        }
        return coordinate(rule, execution, key, true);
    }

    private <T> T coordinate(CompiledRule rule, SingleFlightExecution<T> execution,
                             String key, boolean includeCache) {
        KvLock lock = tryAcquire(rule, key);
        if (lock == null) {
            return onContention(rule, execution, key);
        }
        try {
            // 抢锁成功后重读会话：从进入协调到抢到锁之间，上一个 leader 可能已发布终态。
            // 直接开新执行会破坏“同 key 只执行一次”，此处复用已发布的结果。
            KvRecord record = rule.coordinationStore.get(sessionKey(key));
            if (record != null) {
                SessionEnvelope session = SessionEnvelope.of(record.getValue());
                if (session.isTerminal()) {
                    return finishSession(session, execution);
                }
            }
            SessionEnvelope pending = SessionEnvelope.pending(lock.token(), clock.millis());
            rule.coordinationStore.put(sessionKey(key), record(pending, sessionTtl(rule)),
                    PutMode.SET);
            return executeLeader(rule, execution, key, lock, pending, includeCache);
        } finally {
            release(lock);
        }
    }

    private KvLock tryAcquire(CompiledRule rule, String key) {
        try {
            return rule.lockManager.tryAcquire(key, rule.rule.getLockLeaseMillis());
        } catch (RuntimeException e) {
            throw storeException(e);
        }
    }

    private <T> T executeLeader(CompiledRule rule, SingleFlightExecution<T> execution,
                                String key, KvLock lock, SessionEnvelope pending,
                                boolean includeCache) {
        T result;
        try {
            result = load(execution);
        } catch (Throwable throwable) {
            writeTerminal(rule, key, pending, SessionEnvelope.failure(lock.token(),
                    safeMessage(throwable), clock.millis()), rule.rule.getFailureTtlMillis());
            throw unchecked(throwable);
        }

        boolean cacheable = isCacheable(rule, execution, result);
        JsonNode resultJson = toJson(result, execution.getReturnType());
        writeTerminal(rule, key, pending, SessionEnvelope.success(
                lock.token(), resultJson, cacheable, clock.millis()),
                rule.rule.getUncacheableTtlMillis());
        if (includeCache && rule.rule.isCacheEnabled() && cacheable) {
            try {
                rule.resultStore.put(cacheKey(key), KvRecord.of(resultJson.toString(),
                        rule.rule.getCacheTtlMillis(), clock.millis()), PutMode.SET);
            } catch (RuntimeException e) {
                KvStoreException storeFailure = storeException(e);
                if (effectiveStoreFailurePolicy(rule.rule) == StoreFailurePolicy.FAIL_CLOSED) {
                    throw new SingleFlightConfigException("Singleflight store failure|point="
                            + execution.getPoint(), storeFailure);
                }
                log.warn("SingleflightEngine|cacheWriteFailurePassThrough|point={}",
                        execution.getPoint(), storeFailure);
            }
        }
        return result;
    }

    private boolean writeTerminal(CompiledRule rule, String key, SessionEnvelope pending,
                                  SessionEnvelope terminal, long ttlMillis) {
        try {
            return rule.cas.compareAndSet(sessionKey(key), pending.toJson(),
                    record(terminal, ttlMillis));
        } catch (RuntimeException e) {
            log.warn("SingleflightEngine|sessionWriteFailed|key={}", key, e);
            return false;
        }
    }

    private <T> T onContention(CompiledRule rule, SingleFlightExecution<T> execution,
                               String key) {
        if (rule.rule.getContention() == ContentionPolicy.FAIL_FAST) {
            throw new SingleFlightConflictException("Singleflight conflict|point="
                    + execution.getPoint() + "|key=" + key);
        }
        if (rule.rule.getContention() == ContentionPolicy.FALLBACK) {
            return cast(fallbackConverter.convert(rule.rule.getFallback(),
                    execution.getReturnType()));
        }
        return waitOrTakeOver(rule, execution, key);
    }

    @SuppressWarnings("unchecked")
    private <T> T waitOrTakeOver(CompiledRule rule, SingleFlightExecution<T> execution,
                                 String key) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(rule.rule.getWaitTimeoutMillis());
        while (System.nanoTime() < deadline) {
            KvRecord record = rule.coordinationStore.get(sessionKey(key));
            if (record != null) {
                SessionEnvelope session = SessionEnvelope.of(record.getValue());
                if (session.isTerminal()) {
                    return finishSession(session, execution);
                }
                if (rule.coordinationStore.get(lockKey(key)) != null) {
                    sleep(rule.rule.getPollIntervalMillis());
                    continue;
                }
            }
            KvLock lock = tryAcquire(rule, key);
            if (lock != null) {
                return coordinateAfterTakeover(rule, execution, key, lock);
            }
            sleep(rule.rule.getPollIntervalMillis());
        }
        throw new SingleFlightTimeoutException("Singleflight wait timeout|point="
                + execution.getPoint() + "|key=" + key
                + "|timeoutMillis=" + rule.rule.getWaitTimeoutMillis());
    }

    private <T> T coordinateAfterTakeover(CompiledRule rule, SingleFlightExecution<T> execution,
                                          String key, KvLock lock) {
        try {
            // 接管前重读会话：从观察到“锁不存在”到真正抢到锁之间，原 leader 可能刚发布终态。
            // 终态已存在则直接复用，避免同 key 二次执行。
            KvRecord record = rule.coordinationStore.get(sessionKey(key));
            if (record != null) {
                SessionEnvelope session = SessionEnvelope.of(record.getValue());
                if (session.isTerminal()) {
                    return finishSession(session, execution);
                }
            }
            SessionEnvelope pending = SessionEnvelope.pending(lock.token(), clock.millis());
            rule.coordinationStore.put(sessionKey(key), record(pending, sessionTtl(rule)),
                    PutMode.SET);
            return executeLeader(rule, execution, key, lock, pending, false);
        } finally {
            release(lock);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T finishSession(SessionEnvelope session, SingleFlightExecution<T> execution) {
        if (SessionEnvelope.STATE_FAILURE.equals(session.state())) {
            throw new SingleFlightExecutionException("Singleflight loader failed|point="
                    + execution.getPoint() + "|error=" + session.errorMessage());
        }
        return (T) decode(session.result().toString(), execution.getReturnType());
    }

    private <T> T onStoreFailure(CompiledRule rule, SingleFlightExecution<T> execution,
                                 KvStoreException e) {
        if (effectiveStoreFailurePolicy(rule.rule) == StoreFailurePolicy.FAIL_CLOSED) {
            throw new SingleFlightConfigException("Singleflight store failure|point="
                    + execution.getPoint(), e);
        }
        log.warn("SingleflightEngine|storeFailurePassThrough|point={}",
                execution.getPoint(), e);
        return load(execution);
    }

    private static StoreFailurePolicy effectiveStoreFailurePolicy(SingleFlightRule rule) {
        if (rule.getOnStoreFailure() != null) {
            return rule.getOnStoreFailure();
        }
        return rule.getContention() == ContentionPolicy.FAIL_FAST
                ? StoreFailurePolicy.FAIL_CLOSED : StoreFailurePolicy.PASS_THROUGH;
    }

    private boolean isCacheable(CompiledRule rule, SingleFlightExecution<?> execution,
                                Object result) {
        if (rule.cacheWhen == null) {
            return true;
        }
        MatchContext context = MatchContext.of(result);
        context.setAttributes(execution.getArguments());
        return rule.cacheWhen.matches(context);
    }

    private boolean matchesSkip(CompiledRule rule, SingleFlightExecution<?> execution) {
        MatchContext context = MatchContext.of(execution.getArguments());
        context.setAttributes(execution.getArguments());
        return rule.skipWhen.matches(context);
    }

    private String renderKey(CompiledRule rule, SingleFlightExecution<?> execution) {
        String rendered;
        if (rule.keyResolver == null) {
            rendered = execution.getPoint();
        } else {
            rendered = rule.keyResolver.render(execution.getArguments());
        }
        if (rendered == null) {
            if (rule.rule.getOnInvalidKey() == InvalidKeyPolicy.PASS_THROUGH) {
                return null;
            }
            throw new SingleFlightConfigException("Singleflight rendered key is invalid"
                    + "|point=" + execution.getPoint() + "|template=" + rule.rule.getKey());
        }
        return SingleFlightKeys.compose(execution.getPoint(), rendered,
                rule.rule.getDigestThreshold());
    }

    private <T> T load(SingleFlightExecution<T> execution) {
        try {
            return execution.getLoader().load();
        } catch (Throwable throwable) {
            throw unchecked(throwable);
        }
    }

    private static JsonNode toJson(Object result, Type returnType) {
        if (result == null || void.class.equals(returnType) || Void.TYPE.equals(returnType)) {
            return NullNode.getInstance();
        }
        Object parsed = JsonUtil.parseObj(JsonUtil.toJsonStr(result));
        if (parsed instanceof JsonNode) {
            return (JsonNode) parsed;
        }
        throw new SingleFlightConfigException(
                "Singleflight result is not JSON-serializable|type=" + result.getClass().getName());
    }

    private Object decode(String json, Type returnType) {
        if (void.class.equals(returnType) || Void.TYPE.equals(returnType)) {
            return null;
        }
        return JsonUtil.toBean(json, returnType);
    }

    private CompiledRule compileRule(String json) {
        SingleFlightRule rule;
        try {
            rule = JsonUtil.toBean(json, SingleFlightRule.class);
        } catch (RuntimeException e) {
            throw new SingleFlightConfigException("Invalid singleflight rule json|json=" + json, e);
        }
        if (rule == null) {
            throw new SingleFlightConfigException("Singleflight rule must not be empty");
        }
        validateRule(rule);
        if (blank(rule.getId())) {
            throw new SingleFlightConfigException("Singleflight rule id must not be empty");
        }

        String storeName = normalizedStoreName(rule.getStore());
        String previousStore = activeStores.putIfAbsent(rule.getId(), storeName);
        if (previousStore != null && !previousStore.equals(storeName)) {
            throw new SingleFlightConfigException("Singleflight store hot-switch is not allowed"
                    + "|point=" + rule.getId() + "|old=" + previousStore + "|new=" + storeName);
        }

        KvStore rawStore = resolveStore(storeName);
        CasCapable cas = requireCas(rawStore);
        KvStore coordinationStore = (KvStore) cas;
        KvLockManager lockManager = lockManagers.computeIfAbsent(storeKey(storeName),
                ignored -> new KvLockManager(coordinationStore, clock,
                        new KvLockManager.Config().setSpace(LOCK_SPACE)));
        try {
            SingleFlightCondition skipWhen = compileCondition(rule.getSkipWhen());
            SingleFlightCondition cacheWhen = compileCondition(rule.getCacheWhen());
            KeyResolver keyResolver = rule.getKey() == null || rule.getKey().trim().isEmpty()
                    ? null : new KeyResolver(rule.getKey());
            return new CompiledRule(rule, skipWhen, cacheWhen, keyResolver,
                    rawStore, coordinationStore, cas, lockManager);
        } catch (RuntimeException e) {
            activeStores.remove(rule.getId(), storeName);
            throw e;
        }
    }

    private static void validateRule(SingleFlightRule rule) {
        positive(rule.getLockLeaseMillis(), "lockLeaseMillis");
        positive(rule.getWaitTimeoutMillis(), "waitTimeoutMillis");
        positive(rule.getPollIntervalMillis(), "pollIntervalMillis");
        positive(rule.getUncacheableTtlMillis(), "uncacheableTtlMillis");
        positive(rule.getFailureTtlMillis(), "failureTtlMillis");
        positive(rule.getDigestThreshold(), "digestThreshold");
        if (rule.isCacheEnabled() && rule.getCacheTtlMillis() <= 0) {
            throw new SingleFlightConfigException(
                    "cacheTtlMillis must be > 0 when cacheEnabled is true");
        }
        if (!rule.isCacheEnabled() && rule.getCacheTtlMillis() != 0) {
            throw new SingleFlightConfigException(
                    "cacheTtlMillis is not allowed when cacheEnabled is false");
        }
        if (rule.getContention() == ContentionPolicy.FALLBACK && rule.getFallback() == null) {
            throw new SingleFlightConfigException("FALLBACK requires fallback json");
        }
    }

    private static void validateRuleForExecution(SingleFlightRule rule,
                                                 SingleFlightExecution<?> execution) {
        boolean voidReturn = void.class.equals(execution.getReturnType())
                || Void.TYPE.equals(execution.getReturnType());
        if (voidReturn && (rule.isCacheEnabled()
                || rule.getContention() != ContentionPolicy.FAIL_FAST)) {
            throw new SingleFlightConfigException(
                    "void method requires cacheEnabled=false and contention=FAIL_FAST");
        }
        if (rule.getContention() == ContentionPolicy.FALLBACK && rule.getFallback() != null
                && rule.getFallback().isNull() && isPrimitive(execution.getReturnType())) {
            throw new SingleFlightConfigException(
                    "Primitive return type does not allow explicit null fallback|returnType="
                            + execution.getReturnType().getTypeName());
        }
    }

    private static boolean isPrimitive(Type type) {
        return type instanceof Class && ((Class<?>) type).isPrimitive();
    }

    private void validateCriterionVariables(SingleFlightCondition condition,
                                            SingleFlightExecution<?> execution,
                                            String field) {
        if (condition == null) {
            return;
        }
        if (!execution.getParameterNames().isEmpty() && "skipWhen".equals(field)) {
            validateVariables(condition, execution.getParameterNames(), field);
        }
    }

    private static void validateVariables(SingleFlightCondition condition,
                                          Set<String> knownNames, String field) {
        for (String name : condition.variableNames()) {
            String variableName = name.startsWith("$") ? name.substring(1) : name;
            if (!knownNames.contains(variableName)) {
                throw new SingleFlightConfigException("Singleflight variable is not resolvable"
                        + "|field=" + field + "|variable=" + name);
            }
        }
    }

    private static SingleFlightCondition compileCondition(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        return SingleFlightCondition.compile(expression);
    }

    private static String normalizedStoreName(String store) {
        return store == null || store.trim().isEmpty() ? "" : store.trim();
    }

    private static String storeKey(String storeName) {
        return storeName.isEmpty() ? "<default>" : storeName;
    }

    private KvStore resolveStore(String storeName) {
        if (storeName.isEmpty()) {
            return defaultCoordinationStore;
        }
        try {
            return unwrapStore(SingleFlightStores.global().resolve(storeName));
        } catch (IllegalArgumentException e) {
            throw new SingleFlightConfigException(
                    "Singleflight store not registered|store=" + storeName, e);
        }
    }

    private static KvStore unwrapStore(KvStore store) {
        return KvStores.innermost(store);
    }

    private static CasCapable requireCas(KvStore store) {
        CasCapable cas = KvStores.capabilityOf(store, CasCapable.class);
        if (cas == null) {
            throw new SingleFlightConfigException(
                    "Singleflight store requires CasCapable|store=" + store.getClass().getName());
        }
        return cas;
    }

    private static void positive(long value, String name) {
        if (value <= 0) {
            throw new SingleFlightConfigException(name + " must be > 0");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static SpaceKey lockKey(String key) {
        return SpaceKey.of(LOCK_SPACE, key);
    }

    private static SpaceKey sessionKey(String key) {
        return SpaceKey.of(SESSION_SPACE, key);
    }

    private static SpaceKey cacheKey(String key) {
        return SpaceKey.of(CACHE_SPACE, key);
    }

    private KvRecord record(SessionEnvelope envelope, long ttlMillis) {
        return KvRecord.of(envelope.toJson(), ttlMillis, clock.millis());
    }

    private static long sessionTtl(CompiledRule rule) {
        return Math.max(rule.rule.getWaitTimeoutMillis()
                + Math.max(rule.rule.getUncacheableTtlMillis(), rule.rule.getFailureTtlMillis()),
                1000L);
    }

    private static void release(KvLock lock) {
        try {
            lock.close();
        } catch (RuntimeException e) {
            log.warn("SingleflightEngine|lockReleaseFailed|lock={}", lock.name(), e);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SingleFlightTimeoutException("Singleflight wait interrupted");
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getName() : throwable.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    private static RuntimeException unchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            return (RuntimeException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        throw new IllegalStateException(throwable);
    }

    private static KvStoreException storeException(RuntimeException e) {
        if (e instanceof KvStoreException) {
            return (KvStoreException) e;
        }
        return new KvStoreException(e.getMessage(), e);
    }

    @Override
    public void close() {
        rules.destroy();
        lockManagers.values().forEach(manager -> {
            try {
                manager.close();
            } catch (RuntimeException e) {
                log.warn("Singleflight lock manager close failed", e);
            }
        });
        lockManagers.clear();
        templates.clear();
        activeStores.clear();
    }

    public void destroy() {
        close();
    }

    /**
     * Rule plus resources prepared at load time. ConfigDrivenRegistry closes replaced
     * instances only after the new instance has been constructed successfully.
     */
    private static final class CompiledRule implements AutoCloseable {

        private final SingleFlightRule rule;
        private final SingleFlightCondition skipWhen;
        private final SingleFlightCondition cacheWhen;
        private final KeyResolver keyResolver;
        private final KvStore resultStore;
        private final KvStore coordinationStore;
        private final CasCapable cas;
        private final KvLockManager lockManager;

        private CompiledRule(SingleFlightRule rule, SingleFlightCondition skipWhen,
                             SingleFlightCondition cacheWhen, KeyResolver keyResolver,
                             KvStore resultStore, KvStore coordinationStore,
                             CasCapable cas, KvLockManager lockManager) {
            this.rule = rule;
            this.skipWhen = skipWhen;
            this.cacheWhen = cacheWhen;
            this.keyResolver = keyResolver;
            this.resultStore = resultStore;
            this.coordinationStore = coordinationStore;
            this.cas = cas;
            this.lockManager = lockManager;
        }

        @Override
        public void close() {
            // KvLockManager is shared per named store and closed by the engine only.
        }
    }
}
