# 实战案例

本章介绍 `team4u-bean` 在轻量级通用 SDK 设计与多源环境解耦中的典型实战。

---

## 案例：通用消息推送 SDK 的零依赖容器设计

### 业务场景
基础架构团队开发了一个通用的推送客户端 SDK（`push-client-sdk`），需要分发给各个业务线使用。部分业务线是传统的 Spring Boot Web 应用，部分业务线是简单的 CLI 批处理工具或纯 Java 守护进程。

要求：
1. SDK 内部不能强依赖 Spring 注解或 Spring 上下文；
2. 在 Spring 环境下，能自动使用业务在 Spring 中配置的自定义推送通道；
3. 在纯 Java / 测试环境下，自动回退至本地默认实现。

---

### SDK 内部实现代码

```java
import com.team4u.framework.bean.BeanManager;

public class PushNotificationClient {

    private final SmsSender smsSender;

    public PushNotificationClient() {
        // 通过 BeanManager 统一获取依赖，SDK 自身不包含任何 Spring 强制依赖
        this.smsSender = BeanManager.getInstance().loadBean(SmsSender.class, () -> {
            // 纯 Java 环境下的默认本地兜底实现
            return new DefaultHttpSmsSender();
        });
    }

    public void send(String mobile, String message) {
        smsSender.send(mobile, message);
    }
}
```

---

### 各环境使用效果

#### 在独立纯 Java 脚本或单元测试中：
无需启动任何容器，直接 `new PushNotificationClient()` 即可秒级完成初始化并运行，使用 `DefaultHttpSmsSender` 发送。

#### 在 Spring Boot 应用中：
业务方如果定义了自己的 Spring 组件：
```java
@Component
public class CustomAliyunSmsSender implements SmsSender {
    @Override
    public void send(String mobile, String message) {
        // 调用阿里云短信 API
    }
}
```
当应用引入 `team4u-bean-spring` 并显式 `@Import(Team4uBeanConfiguration.class)` 后，`BeanManager.getInstance().loadBean(SmsSender.class, ...)` 会优先从 Spring 容器检索到 `CustomAliyunSmsSender`（`order=100` 优先于 `LocalBeanContainer` 的 `MAX_VALUE`），从而实现透明无缝的定制替换！
