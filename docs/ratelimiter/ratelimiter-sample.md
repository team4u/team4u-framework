# 限流实战案例与最佳实践

在高并发电商大促、秒杀抢购与开放平台 API 场景下，合理的限流策略能够保障核心链路的绝对可用。本文通过两个经典实战案例演示 `team4u-ratelimiter` 的生产落地。

---

## 案例 1：电商大促秒杀防刷与平滑排队

### 场景诉求
- 秒杀商品接口面临瞬时百万 QPS 冲击；
- 单个用户 3 秒内最多点击 1 次；
- 单个 IP 每秒最多发起 10 次请求；
- 被限流的用户返回友好的排队提示，不直接返回 500。

### 实战代码

```java
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 复合维度限流：先按 IP 限流，再按用户限流
     */
    @PostMapping("/rush")
    @RateLimit(key = "'ip:' + #request.remoteAddr", limit = 10, period = 1, fallback = "rushIpFallback")
    @RateLimit(key = "'user:' + #userId", limit = 1, period = 3, fallback = "rushUserFallback")
    public ApiResponse<SeckillReceipt> rushBuy(
            HttpServletRequest request,
            @RequestParam String userId,
            @RequestParam String skuId) {
        
        SeckillReceipt receipt = seckillService.executeRush(userId, skuId);
        return ApiResponse.success(receipt);
    }

    @RateLimitReject
    public ApiResponse<SeckillReceipt> rushUserFallback(HttpServletRequest request, String userId, String skuId) {
        return ApiResponse.fail(429, "排队中，请不要频繁点击哦");
    }

    @RateLimitReject
    public ApiResponse<SeckillReceipt> rushIpFallback(HttpServletRequest request, String userId, String skuId) {
        return ApiResponse.fail(429, "当前网络繁忙，请稍后再试");
    }
}
```

---

## 案例 2：开放平台 API 租户分级限流

### 场景诉求
- 开放平台对第三方开发者按租户（Tenant）进行配额控制；
- 普通租户：100 QPS 令牌桶；
- VIP 租户：1000 QPS 令牌桶；
- 动态获取租户配额并实时校验。

```java
@Service
public class OpenApiGateway {

    @Autowired
    private TenantConfigService tenantConfigService;

    public void checkTenantLimit(String tenantId, String apiName) {
        int quota = tenantConfigService.getQuota(tenantId);
        
        RateLimitRule rule = RateLimitRule.builder()
                .capacity(quota)
                .refillRate(quota)
                .build();

        RateLimitResult result = RateLimiters.tokenBucket().tryAcquire("open:" + tenantId, rule);
        
        if (!result.isAllowed()) {
            throw new RateLimitException("TENANT_QUOTA_EXCEEDED", 
                    "租户 " + tenantId + " 配额超限，当前上限: " + quota + " QPS");
        }
    }
}
```

---

## 关联章节与进一步阅读

- 了解限流算法原理：[限流算法深度解析](ratelimiter-algorithms.md)
- 了解声明式注解与代理降级：[声明式注解与代理降级](ratelimiter-declarative.md)
- 了解 Spring 自动装配：[Spring 集成与自动装配](ratelimiter-spring.md)
- 了解结果模型与异常诊断：[限流结果模型、原因码与异常处理](ratelimiter-diagnostics.md)
