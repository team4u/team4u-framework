# 轻量级配置仓库 AbstractJsonConfigRepository

`AbstractJsonConfigRepository<T>` 是 `team4u-config` 提供的 JSON 配置仓库抽象模板，解决“**一个配置键承载一份数据表**”的场景：从 `ConfigManager` 读取指定配置键的 JSON 内容，反序列化为目标类型，成功后原子替换内存引用，并挂载配置监听实现热更新。

典型数据形态：规则表、开关集、名单映射、阈值参数集——它们是**纯数据**，不含连接池、线程池等底层资源，变更时不需要“重建对象、销毁旧对象”，只需整体换一份新数据。

---

## 与 ConfigDrivenRegistry 的边界

`AbstractJsonConfigRepository` 与 `ConfigDrivenRegistry` 共享“读配置 -> 监听变更 -> 失败保旧”的外形，但管理目标物完全不同：

| 维度 | `ConfigDrivenRegistry<T>` | `AbstractJsonConfigRepository<T>` |
| :--- | :--- | :--- |
| 管理对象 | 活的运行时组件实例（连接池、HTTP 客户端、路由器） | 纯数据快照（一张规则表、一个名单映射） |
| 变更时的动作 | 调用工厂构建新实例，旧实例执行 `close()` 释放底层资源 | 反序列化 JSON 并原子替换内存引用 |
| 读取方式 | `get(key)` 按需惰性构建，命中缓存后 O(1) | `get()` 直接返回当前快照，无锁读 volatile 引用 |
| 实例形态 | 一个键或一个子前缀对应一个实例，天然多实例 | 全局仅一份，单例 |
| 典型使用者 | 动态连接池、动态路由表、限流规则引擎 | 脱敏规则、日志代理规则、FinOps 配置 |

**选型口诀**：配置变更是“换零件”（连接池、客户端）用 `ConfigDrivenRegistry`；只是“换参数表”（规则、名单、阈值）用 `AbstractJsonConfigRepository`；纯属性绑定读取用动态代理 `createProxy`。

---

## 子类最小实现

模板收编了 init、stop、解析、监听、降级的全部公共骨架，子类只需声明差异部分：

```java
import com.team4u.framework.base.util.TypeReference;
import java.util.Collections;
import java.util.Map;

public class FeatureRuleRepository extends AbstractJsonConfigRepository<Map<String, FeatureRule>> {

    @Override
    protected String configKey() {
        return "app.feature.rules"; // 必须实现：该仓库绑定的配置键
    }

    @Override
    protected TypeReference<Map<String, FeatureRule>> typeReference() {
        // 提供后自动走 JsonUtil 反序列化；不提供则需覆写 parseJson
        return new TypeReference<Map<String, FeatureRule>>() { };
    }

    @Override
    protected Map<String, FeatureRule> emptyConfig() {
        // 可选：配置为空或被删除时的缺省值（默认 null）
        return Collections.emptyMap();
    }

    @Override
    protected void onConfigLoaded(Map<String, FeatureRule> oldValue,
                                  Map<String, FeatureRule> newValue) {
        // 可选：变更回调（首次加载、热更新、stop 重置均触发）
    }
}
```

四个模板方法中仅 `configKey()` 必须实现，其余按需覆写：

| 模板方法 | 必要性 | 职责 |
| :--- | :--- | :--- |
| `configKey()` | 必须 | 声明仓库绑定的配置键 |
| `typeReference()` | 二选一 | 提供反序列化目标类型，模板自动按 JSON 反序列化 |
| `parseJson(String)` | 二选一 | 自定义解析或校验逻辑（如表达式预编译、逐条过滤）时覆写 |
| `emptyConfig()` | 可选 | 配置为空或被删除时的缺省值，默认 null |
| `onConfigLoaded(...)` | 可选 | 配置成功加载后的变更回调 |

---

## 生命周期与线程模型

```java
// 启动：同步完成首次加载并挂载配置监听
featureRuleRepository.init(configManager);

// 运行期：任意线程无锁读取当前生效快照
Map<String, FeatureRule> rules = featureRuleRepository.get();

// 停止：幂等注销监听并重置为缺省配置
featureRuleRepository.stop();
```

- **init/stop 互斥同步**：重复调用 init 会先释放旧的监听与状态，支持底层配置管理器热切换；
- **get 无锁读取**：内部以 volatile 引用承载当前快照，热更新成功后的替换对所有读线程立即可见；
- **回调线程**：`onConfigLoaded` 在配置变更线程执行，实现方需自行保证线程安全。

---

## 统一降级语义

模板将三个层级的失败处理定为一套标准，所有子类行为一致：

| 场景 | 行为 | 设计意图 |
| :--- | :--- | :--- |
| 首次 `init()` 加载失败 | 抛出异常 | 启动期快速失败，杜绝带病上线 |
| 运行期热更新失败 | 保留旧配置并记录警告日志 | 服务连续性优先，坏配置不打断线上 |
| 配置被删除或置空 | 回退 `emptyConfig()` 缺省值 | 优雅降级 |

异常传播约定：子类在 `parseJson` 中抛出的语义化异常（如参数校验失败）原样向上传播，保住子类对外的异常契约；仅裸解析失败（JSON 语法错误、缺失类型引用）由模板包装为 `IllegalStateException`。

---

## 框架内的真实使用者

| 仓库 | 模块 | 配置键 | 承载数据 |
| :--- | :--- | :--- | :--- |
| `MaskRuleRepository` | mask | `team4u.mask.rules` | 第三方类与字段的脱敏规则表 |
| `ProxyRuleRepository` | log 治理 | `team4u.log.proxy` | 日志代理类的代理规则表 |
| `FinOpsConfigRepository` | log 治理 | `team4u.log.finops` | 日志 FinOps 开关与阈值配置 |
| `TargetedDyeingInterceptor` | log 治理 | `team4u.log.dyeing` | 日志染色拦截规则 |

以脱敏规则仓库为例，其配置中心内容形如：

```properties
team4u.mask.rules={"com.example.User":{"phone":"MASK_PHONE","idCard":"MASK_ID_CARD"}}
```

`MaskBootstrap` 启动时调用 `MaskRuleRepository.getInstance().init(configManager)` 完成挂载，此后运维在配置中心修改规则，脱敏行为秒级生效且失败保旧。
