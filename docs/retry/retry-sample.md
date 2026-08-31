# 实战案例

三个常见场景：短时网络抖动、支付后异步补偿、Spring 代理里的不可序列化上下文。

## 验证码短信：INLINE

用户注册时必须当场知道验证码是否发送成功，所以选 INLINE。这里只重试网络超时，不重试参数错误。

```java
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;

import java.io.IOException;
import java.net.SocketTimeoutException;

public class SmsService {

    private static final RetryPolicy SMS_POLICY = RetryPolicy.builder()
            .maxRetries(2) // 总共最多发送 3 次
            .backoff(Backoffs.exponential(100, 2.0, 1000))
            .retryOn(SocketTimeoutException.class)
            .retryOn(IOException.class)
            .abortOn(IllegalArgumentException.class)
            .build();

    private final ThirdPartySmsClient smsClient;

    public SmsService(ThirdPartySmsClient smsClient) {
        this.smsClient = smsClient;
    }

    public boolean sendVerifyCode(String mobile, String code) throws Exception {
        return Retries.inline()
                .policy(SMS_POLICY)
                .call(() -> smsClient.send(mobile, code));
    }
}
```

如果第一次超时、第二次成功，用户这次注册请求会成功，总共调用短信客户端两次。参数错误会立即抛出，不会浪费短信额度。

## 支付成功 Webhook：MANAGED

支付完成后要通知商户。用户请求不能一直等商户服务恢复，且服务重启后也必须继续补发，所以选 MANAGED。

前台提交代码：

```java
import com.team4u.framework.retry.managed.ManagedSubmitResult;
import com.team4u.framework.retry.managed.ManagedRetries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;

public class MerchantNotifyService {

    private static final RetryPolicy NOTIFY_POLICY = RetryPolicy.builder()
            .maxRetries(5)            // 总尝试上限 6 次
            .foregroundMaxRetries(0)  // 首次失败后立即交后台
            .backoff(Backoffs.exponentialJitter(1000, 3.0, 30 * 60 * 1000L))
            .build();


    private final ManagedRetryClient retryClient;
    private final MerchantWebhookClient webhookClient;

    public MerchantNotifyService(
            ManagedRetryClient retryClient,
            MerchantWebhookClient webhookClient) {
        this.retryClient = retryClient;
        this.webhookClient = webhookClient;
    }

    public void notifyMerchant(String orderId, String payload) {
        ManagedSubmitResult<String> result = ManagedRetries.with(retryClient)
                .taskType("merchant-webhook-notify")
                .idempotencyKey("notify-" + orderId)
                .payload(payload)
                .policy(NOTIFY_POLICY)
                .call(() -> {
                    webhookClient.post(payload);
                    return "accepted";
                });

        if (result.isCompleted()) {
            System.out.println("webhook sent now: " + orderId);
        } else if (result.isAccepted()) {
            System.out.println("webhook accepted: " + orderId);
        } else if (result.isFailed()) {
            Throwable error = ((ManagedSubmitResult.Failed<String>) result).getError();
            System.err.println("webhook failed: " + error.getMessage());
        } else if (result.isExisting()) {
            System.out.println("webhook already accepted: " + orderId);
        } else {
            String reason = ((ManagedSubmitResult.Rejected<String>) result).getReason();
            throw new IllegalStateException("webhook rejected: " + reason);
        }
    }
}
```


后台恢复处理器使用同一套发送逻辑：

```java
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;

public final class MerchantWebhookRecoveryHandler implements StringRecoveryHandler {

    private final MerchantWebhookClient webhookClient;

    public MerchantWebhookRecoveryHandler(MerchantWebhookClient webhookClient) {
        this.webhookClient = webhookClient;
    }

    @Override
    public String taskName() {
        return "merchant-webhook-notify";
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        webhookClient.post(payload);
    }
}
```

把这个 handler 注册到运行 `ManagedRetryRuntime` 的进程。前台第一次失败后返回 `Accepted`，后台在第 2 次尝试开始前随机等待约 1 到 3 秒；后续间隔逐步放大，最多等 30 分钟。

### 必须幂等

商户服务收到的通知可能会重复。例如 Worker 调用成功但结果写回前进程崩溃，任务会被再次接管。商户侧应按 `orderId` 或通知流水号判重；重复通知时直接确认，不要重复扣款或重复改库存。

## Spring 声明式代理：忽略请求上下文

银行打款方法中携带 `HttpServletRequest`。HTTP 请求对象不能跨进程恢复，所以用 `@RetryIgnore` 排除：

```java
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

@Service
public class BankTransferService {

    @Retryable(policy = "bank-transfer-policy", mode = RetryMode.MANAGED)
    public void transferMoney(
            String transferId,
            Long amountInCents,
            String bankAccount,
            @RetryIgnore HttpServletRequest request) {
        bankClient.transfer(transferId, amountInCents, bankAccount);
    }
}
```

结果：

- 前台打款成功：方法正常返回。
- 前台失败且策略允许重试：当前 HTTP 请求立即返回，后台稍后用 `transferId/amountInCents/bankAccount` 回放方法。
- 后台回放时 `request` 为 `null`，方法内部不能读取它。

Spring MANAGED 代理必须显式装配 `ManagedRetryRuntime`，并注册 `InvocationReplay`，见[Spring 整合](retry-spring.md)。
