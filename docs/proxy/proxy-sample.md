# 实战案例

本章介绍 `team4u-proxy` 在多种复杂业务场景中的应用实践。

---

## 动态算法/策略热切换 (HotSwap)

### 业务场景
推荐引擎需要在线平滑切换排序算法模型（从协同过滤 `V1` 切换到深度模型 `V2`），要求不重启进程、调用方无感知、且在切换瞬间无请求报错。

### 代码实现
```java
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.support.Swappable;

public class RecommendationServiceHolder {

    public interface RecommendEngine {
        List<String> recommend(String userId);
    }

    private final RecommendEngine engineProxy;

    public RecommendationServiceHolder() {
        RecommendEngine v1Engine = userId -> List.of("Item-A", "Item-B");
        
        // 构造支持热切换的引擎代理
        this.engineProxy = ProxyBuilder.forClass(RecommendEngine.class)
                .delegate(v1Engine)
                .enableHotswap()
                .build();
    }

    public List<String> getRecommendations(String userId) {
        return engineProxy.recommend(userId);
    }

    /**
     * 收到配置中心通知或运维指令后，动态更新底层引擎
     */
    public void switchEngine(RecommendEngine newEngine) {
        Swappable swappable = (Swappable) engineProxy;
        Object previousEngine = swappable.hotswap(newEngine);
        System.out.println("成功完成算法热切换，已替换旧实例: " + previousEngine);
    }
}
```

---

## 不可变配置对象的零 NPE 防御 (asEmptyObject)

### 业务场景
服务启动时从远程加载配置树，某些业务线可能未配置 `cluster.metrics.reporter`。业务代码直接链式调用 `config.getCluster().getMetrics().getReporter().isEnabled()`，需要彻底杜绝 `NullPointerException`。

### 代码实现
```java
import com.team4u.framework.proxy.ProxyBuilder;

public class ConfigLoader {

    public static class AppConfig {
        public ClusterConfig getCluster() { return null; }
    }

    public static class ClusterConfig {
        public MetricsConfig getMetrics() { return null; }
    }

    public static class MetricsConfig {
        public ReporterConfig getReporter() { return null; }
    }

    public static class ReporterConfig {
        public boolean isEnabled() { return true; }
        public String getHost() { return "127.0.0.1"; }
    }

    public static void main(String[] args) {
        // 创建空对象防御代理
        AppConfig safeConfig = ProxyBuilder.forClass(AppConfig.class)
                .asEmptyObject()
                .build();

        // 即使各层均为 null，级联调用依然安全！
        boolean enabled = safeConfig.getCluster().getMetrics().getReporter().isEnabled();
        String host = safeConfig.getCluster().getMetrics().getReporter().getHost();

        System.out.println("安全 enabled: " + enabled); // 输出: false
        System.out.println("安全 host: '" + host + "'"); // 输出: ''
    }
}
```

---

## 统一方法执行耗时与审计监听器 (Tracker)

### 业务场景
为数据同步组件的方法调用挂载审计日志，记录每张表同步的耗时并在发生异常时报警。

### 代码实现
```java
import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.support.Tracker;
import java.lang.reflect.Method;

public class DataSyncService {

    public interface SyncTask {
        int syncTable(String tableName);
    }

    public static void main(String[] args) {
        SyncTask rawTask = tableName -> {
            // 模拟同步耗时
            Thread.sleep(50);
            return 1000; // 同步行数
        };

        SyncTask monitoredTask = ProxyBuilder.forClass(SyncTask.class)
                .delegate(rawTask)
                .withTracker(new Tracker() {
                    private final ThreadLocal<Long> timer = new ThreadLocal<>();

                    @Override
                    public void before(Object proxy, Method method, Object[] args) {
                        timer.set(System.currentTimeMillis());
                        System.out.println("[Audit] 开始同步表: " + args[0]);
                    }

                    @Override
                    public void after(Object proxy, Method method, Object[] args, Object result) {
                        long cost = System.currentTimeMillis() - timer.get();
                        timer.remove();
                        System.out.printf("[Audit] 表 %s 同步完成，同步行数: %s, 耗时: %d ms%n", 
                                args[0], result, cost);
                    }

                    @Override
                    public void onException(Object proxy, Method method, Object[] args, Throwable e) {
                        timer.remove();
                        System.err.printf("[Audit] 表 %s 同步异常: %s%n", args[0], e.getMessage());
                    }
                })
                .build();

        monitoredTask.syncTable("orders_202608");
    }
}
```
