# SPI 扩展与优先级排序

`BeanManager` 支持通过 Java 标准 SPI（`ServiceLoader`）加载外部自定义的 `BeanFactory` 实现，满足从分布式配置中心、JNDI 或自定义容器中检索 Bean 的扩展诉求。

---

## 实现自定义 `BeanFactory`

```java
package com.mycompany.container;

import com.team4u.framework.bean.core.BeanFactory;
import java.util.Collections;
import java.util.Map;

public class CustomJndiBeanFactory implements BeanFactory {

    @Override
    public int getOrder() {
        // 设置为 50，优先级高于 SpringBeanContainer (100) 与 LocalBeanContainer (MAX_VALUE)
        return 50;
    }

    @Override
    public <T> T getBean(String name) {
        // 自定义 JNDI 或远程服务查找逻辑
        return null;
    }

    @Override
    public <T> T getBean(Class<T> type) {
        return null;
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        return Collections.emptyMap();
    }
}
```

---

## 注册 SPI 配置文件

在项目资源目录 `resources/META-INF/services/com.team4u.framework.bean.core.BeanFactory` 中写入自定义实现类的全限定名：

```text
com.mycompany.container.CustomJndiBeanFactory
```

---

## Order 优先级排序与多源聚合规则

`BeanManager` 启动时通过 `ServiceLoader.load(BeanFactory.class)` 自动发现所有扩展并按 `getOrder()` 升序排列：

```java
private void sortFactories() {
    factories.sort(Comparator.comparingInt(BeanFactory::getOrder));
}
```

### 检索与聚合语义：
- **单 Bean 查找 (`getBean(name)` / `getBean(type)`)**：
  按 `getOrder()` 从小到大依次遍历各个工厂，一旦某个工厂返回非 `null` 实例，立即短路返回。
- **多 Bean 聚合 (`getBeansOfType(type)`)**：
  将所有工厂检索到的 Bean 汇聚为一个 `Map<String, T>`。如果出现同名冲突，**高优先级容器中的 Bean 优先保留**（通过 `(v1, v2) -> v1` 策略解决冲突）。
