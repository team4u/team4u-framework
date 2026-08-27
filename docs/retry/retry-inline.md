# 进程内重试（INLINE）

INLINE 在当前 JVM 里完成全部尝试。它适合必须拿到返回值的调用，例如查询外部接口、写数据库、发验证码；代价是调用方要等待每次尝试和间隔。

## 怎么用

```java
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

import java.io.IOException;

RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(2)
        .backoff(Backoffs.exponential(100, 2.0, 1000))
        .retryOn(IOException.class)
        .abortOn(IllegalArgumentException.class)
        .build();

String body = Retries.inline()
        .policy(policy)
        .call(() -> httpClient.get("https://api.example.com/orders/1001"));
```

执行规则：

- 先执行一次业务代码。
- 失败后按 `RetryPolicy` 判断是否重试，并按 `Backoffs` 计算等待时间。
- 成功时返回业务返回值。
- 不可重试或次数耗尽时，抛出解包后的业务异常。
- `InterruptedException` 和 JVM `Error` 不会被当作普通业务失败重试。

`maxRetries=2` 表示首次执行后最多再试 2 次；总执行上限是 3 次。

## 异常怎么匹配

框架先把 `CompletionException`、`ExecutionException`、`InvocationTargetException`、`UndeclaredThrowableException` 等包装层层剥开，再用最里面的业务异常做判断。

| 配置 | 未配置时的行为 | 配置后的行为 |
| :--- | :--- | :--- |
| `retryOn(IOException.class)` | 所有普通异常都可重试 | 只有命中这些类型才重试 |
| `abortOn(IllegalArgumentException.class)` | 不额外拦截 | 命中立即终止，不再重试 |
| `condition("retryCount <= 2")` | 不做表达式判断 | 类型判断通过后，再计算表达式 |

同一个异常同时命中 `abortOn` 和 `retryOn` 时，`abortOn` 优先。中断会恢复线程中断标记并立即终止。

### condition

`condition` 用于补充业务判断。可用变量：

| 变量 | 含义 |
| :--- | :--- |
| `retryCount` | 已失败的次数减 1，第一次重试前为 `0` |
| `maxRetries` | 配置的最大重试次数，不包含首次 |
| `cause` | 解包后的业务异常 |
| `message` | 异常消息，`null` 会被替换为空字符串 |

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(3)
        .backoff(Backoffs.fixed(100))
        .retryOn(IOException.class)
        .condition("retryCount <= 2 && message contains 'timeout'")
        .build();
```

## 同步与异步

`call` 在当前线程执行，实现上就是“执行、失败、等待、再执行”。

```java
String result = Retries.inline()
        .policy(policy)
        .call(() -> orderClient.query("1001"));
```

`callAsync` 接收返回 `CompletableFuture` 的任务。等待退避时间时占用框架调度线程，不会阻塞当前请求线程。

```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<String> future = Retries.inline()
        .policy(policy)
        .callAsync(() -> orderClient.queryAsync("1001"));

future.thenAccept(body -> System.out.println("result=" + body));
```

调用方取消返回的 `future` 时，框架会联动取消当前执行中的 Future 和还在调度的重试任务。异步方法的返回类型建议使用 `CompletableFuture<T>`，不要在内部自己再套一层重试。

## 什么时候该换 MANAGED

满足任意一条就应该考虑 MANAGED：

- 总等待时间可能超过用户请求能接受的时长。
- 进程重启后仍要继续补偿。
- 前台只需要快速尝试一次，后续可以由后台慢慢执行。

MANAGED 需要 payload、幂等键和后台恢复处理器，接入方式见[托管持久化重试](retry-managed.md)。
