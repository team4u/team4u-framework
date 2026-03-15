# team4u-log FAQ

## 为什么日志没打印出来？

先排查这几项：

- 当前 logger 级别是否允许这条日志输出。
- 是否还没执行到 `log()`。
- 是否被异常限流标记为抑制。
- 是否在测试或嵌入式环境里提前 `stop()` 了模块。

如果你怀疑是级别判定导致，可以先确认对应类的日志级别配置；如果怀疑是限流，可继续看“为什么异常日志突然少了？”。

## 为什么日志级别被改成了 `DEBUG` / `TRACE` / `WARN`？

常见原因有三类：

- 命中了 `team4u.log.dyeing`，被动态提权到 `DEBUG` 或 `TRACE`
- `@AutoLogTrace` 命中了 `slowThreshold`，成功日志变成 `WARN`
- `@AutoLogTrace` 命中了 `ignoreExceptions`，业务异常被降为 `WARN`

排查时优先看：

- 当前是否配置了 `team4u.log.dyeing`
- 日志里是否带有命中的染色规则标记
- 相关方法是否使用了 `@AutoLogTrace`

## 为什么字段没脱敏？

先看字段属于哪一类：

- DTO 字段：是否加了 `@Mask`
- Map Key / 方法参数 / 第三方类字段：是否配置了 `team4u.mask.rules`

再看这几个细节：

- 动态规则是否已生效
- 规则 key 是否和字段名一致
- 如果依赖方法参数名，编译时是否开启了 `-parameters`

一个常见误区是：代码里参数叫 `mobile`，但编译后参数名变成 `arg0`，这时按 `mobile` 配规则不会命中。

## 为什么方法参数名变成了 `arg0`、`arg1`？

这是 Java 编译时没有保留真实参数名。解决方式是在 `pom.xml` 里打开 `-parameters`：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>-parameters</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

如果不加这个参数：

- 日志里的参数名可读性会下降
- 基于参数名的动态脱敏规则会更难命中

Spring AOP 不会自动修复这个问题。即使 `@AutoLogTrace` 通过 Spring Bean 生效，参数名保留仍然取决于业务工程自己的编译参数。

## 为什么 Spring Bean 上的 `@AutoLogTrace` 没生效？

优先排查这几项：

- 是否显式 `@Import(LogSpringConfiguration.class)`
- 当前对象是否真的是 Spring 容器管理的 Bean
- 是否属于同类自调用，导致没有经过 Spring 代理
- 方法是否是 `private` / `final`

如果你已经是 Spring Bean 场景，不要再额外调用 `LogProxyFactory.createProxy()`；两种入口叠加会导致重复日志。

## 为什么染色规则写了 `action == 'CreateOrder'` 却不生效？

因为日志元数据必须写成 `meta_*`，不能直接写裸字段名。

正确写法：

```text
meta_action == 'CreateOrder'
```

可以直接访问的通常是 `payload` 里的业务字段，例如：

```text
orderId == 'ORD-1001'
```

简单记法：

- 业务字段看 `payload`
- 链路字段看 MDC
- 日志自身属性看 `meta_*`

## 为什么日志被截断了？

这是保护机制，不一定是异常：

- 单个字符串字段太长，会命中 `maxStringLength`
- 整条 JSON 太长，会命中 `maxLogLength`
- `byte[]` 会被替换成大小提示，而不是原样展开

相关配置在 `team4u.log.finops`：

```json
{
  "maxLogLength": 5000,
  "maxStringLength": 2000,
  "errorLimitPerSecond": 10
}
```

如果业务上确实需要更长的日志，应该调整阈值，而不是绕过保护机制。

## 为什么异常日志突然少了？

最常见原因是命中了异常限流。框架会按“业务动作 + 异常类型”做错误风暴防护，超过阈值后会抑制后续日志。

重点排查：

- 是否出现了同类型异常的瞬时爆发
- `team4u.log.finops.errorLimitPerSecond` 是否过低
- 当前异常是否本来就被 `ignoreExceptions` 降级为业务异常

如果你在压测或故障演练场景里需要更高阈值，可以临时调大 `errorLimitPerSecond`。

## 为什么 `traceId` 没带上？

默认行为是从 MDC 里的 `traceId` 读取，所以先确认：

- 业务线程里是否真的 `MDC.put("traceId", "...")`
- 是否发生了线程切换但 MDC 没透传
- 是否已经把提取 key 改成了别的名字

如果你的链路字段不是 `traceId`，可以通过 `MdcEnrichInterceptor` 调整：

```java
MdcEnrichInterceptor.getInstance().setTraceIdKey("requestId");
```

## 为什么 `derive()` 后改了子日志，模板里的对象也跟着变了？

因为 `derive()` 只复制顶层 `payload` Map，不会深拷贝内部对象。

例如模板里放了一个可变 `List` 或 DTO：

- 新旧日志实例的顶层 key 不会互相污染
- 但内部对象引用仍可能共享

推荐做法：

- 模板里只放不可变值
- 可变对象在每次 `derive()` 后重新放入

## 遇到问题时的排查顺序

建议按这个顺序排：

1. 先确认有没有执行到 `log()` 或代理拦截点。
2. 再看 logger 级别和 `@AutoLogTrace` 注解参数。
3. 再看配置中心规则是否已生效。
4. 最后排查限流、截断、MDC 透传和参数名保留。

如果你需要完整的业务接入路径，参考 [walkthrough.md](./walkthrough.md)；如果你需要看底层机制，参考 [architecture.md](./architecture.md)。
