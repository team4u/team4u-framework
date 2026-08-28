# 快速开始

本文介绍如何在纯 Java 与 Spring 环境下使用 `team4u-bean` / `team4u-bean-spring` 进行对象管理。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-bean</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## 纯 Java 环境下的本地单例管理

在没有 Spring 的独立脚本、批处理或 CLI 环境中，直接使用 `BeanManager` 提供的本地单例容器：

```java
import com.team4u.framework.bean.BeanManager;

public class BeanQuickStart {

    public static void main(String[] args) {
        BeanManager manager = BeanManager.getInstance();

        // 1. 手动向本地容器注册单例 Bean
        manager.registerBean("userService", new UserServiceImpl());

        // 2. 按名称查找
        UserService s1 = manager.getBean("userService");

        // 3. 按类型安全查找
        UserService s2 = manager.getBean(UserService.class);

        // 4. 强校验查找（未找到时抛出 NoSuchBeanDefinitionException）
        UserService s3 = manager.getRequiredBean(UserService.class);

        // 5. 延迟原子懒加载（若容器中已存在则直接返回，不存在时执行 Supplier 并原子注册）
        DatabasePool pool = manager.loadBean(DatabasePool.class, () -> {
            System.out.println("执行耗时的数据源连接池初始化...");
            return new DatabasePool("jdbc:mysql://localhost:3306/db");
        });
    }
}
```

---

## Spring 环境显式桥接

当你的通用 SDK 运行在 Spring 环境中时，添加 `team4u-bean-spring`，并在应用配置类显式导入共享配置：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-bean-spring</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
import com.team4u.framework.bean.spring.Team4uBeanConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(Team4uBeanConfiguration.class)
public class FrameworkBeanConfig {
}
```

`Team4uBeanConfiguration` 会注册唯一的 `SpringBeanContainer`。该 Bean 被 Spring 初始化时自动注入 `ApplicationContext` 并挂载到 `BeanManager`。
在任何业务代码或底层 SDK 内部，无需引入 `@Autowired` 注解，即可透明读取 Spring 托管的 Bean：

```java
import com.team4u.framework.bean.BeanManager;

public class CommonSdkService {

    public void doAction() {
        // 自动穿透到 Spring ApplicationContext 查找 OrderService
        OrderService orderService = BeanManager.getInstance().getBean(OrderService.class);
        orderService.handle();
    }
}
```

---

## 下一步

- 深入本地容器与并发设计：[本地容器与原子懒加载](bean-container.md)
- 探索 Spring 容器无缝桥接与生命周期：[Spring 容器无缝桥接](bean-spring.md)
- 扩展自定义 SPI 容器：[SPI 扩展与优先级排序](bean-spi.md)
