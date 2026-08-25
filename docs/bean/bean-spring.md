# Spring 容器无缝桥接

当开发一个供多个业务团队使用的通用中间件或 SDK 时，宿主工程通常是 Spring Boot 项目。`SpringBeanContainer` 实现了“两套体系，一套接口”的透明桥接。

---

## 桥接工作原理

```mermaid
sequenceDiagram
    autonumber
    participant App as 业务 / SDK 代码
    participant BM as BeanManager 门面
    participant SC as SpringBeanContainer (order=100)
    participant SpCtx as Spring ApplicationContext
    participant LC as LocalBeanContainer (order=MAX)

    Note over SC,SpCtx: Spring 启动并注入 ApplicationContext
    SC->>BM: 自动调用 addProvider(this) 注册自身并重排序

    App->>BM: getBean(OrderService.class)
    BM->>SC: 1. 优先向 SpringBeanContainer 查找
    SC->>SpCtx: getBean(OrderService.class)
    alt Spring 容器中存在
        SpCtx-->>SC: 返回 Spring 托管 Bean
        SC-->>BM: 返回 Bean
        BM-->>App: 返回 Bean
    else Spring 容器中未找到
        SC-->>BM: 返回 null
        BM->>LC: 2. 回退向 LocalBeanContainer 查找
        LC-->>BM: 返回本地 Bean
        BM-->>App: 返回 Bean
    end
```

---

## `SpringBeanContainer` 核心实现细节

```java
package com.team4u.framework.bean.provider;

public class SpringBeanContainer implements BeanFactory, BeanRegistry, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        // 自动将当前容器注入全局 BeanManager 门面并重排优先级
        BeanManager.getInstance().addProvider(this);
    }

    @Override
    public int getOrder() {
        return 100; // 优先级高于 LocalBeanContainer (Integer.MAX_VALUE)
    }

    @Override
    public <T> boolean registerBean(String beanName, T bean) {
        if (!isContextActive()) {
            return false;
        }

        // 向 Spring 运行时动态注册单例 Bean
        if (applicationContext instanceof ConfigurableApplicationContext) {
            ConfigurableListableBeanFactory beanFactory = 
                    ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
            if (!beanFactory.containsSingleton(beanName)) {
                beanFactory.registerSingleton(beanName, bean);
                return true;
            }
        }
        return false;
    }
}
```

---

## 接入方式

在 Spring Boot 应用的配置类中注册 `SpringBeanContainer`：

```java
import com.team4u.framework.bean.provider.SpringBeanContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Team4uBeanAutoConfiguration {

    @Bean
    public SpringBeanContainer springBeanContainer() {
        return new SpringBeanContainer();
    }
}
```

### 核心优势
- **完全解耦**：底层 SDK 内部无需在代码中引入任何 Spring 强依赖注解（如 `@Autowired`、`@Component`），直接使用 `BeanManager.getInstance().getBean(...)`；
- **环境自适应**：在单元测试或纯 Java 运行环境下，代码自动回退到 `LocalBeanContainer`，保证测试和脚本秒级启动。
