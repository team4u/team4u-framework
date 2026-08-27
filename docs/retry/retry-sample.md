# 实战案例

本章介绍 `team4u-retry` 在外部 API 即时容灾、支付通知可靠补偿及 Spring 声明式代理中的典型用法。

## 第三方短信发送即时容灾 (INLINE)

用户注册时发送验证码短信。当下游短信通道因网络闪断超时时，当前线程按指数退避重试。`maxRetries=2` 表示首次执行后最多再重试 2 次，总尝试上限为 3 次。

```java
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;

@Service
public class SmsService {

    @Autowired
    private ThirdPartySmsClient smsClient;

    private static final RetryPolicy SMS_RETRY_POLICY = RetryPolicy.builder()
            .maxRetries(2)
            .backoff(Backoffs.exponential(100, 2.0, 1000))
            .retryOn(SocketTimeoutException.class)
            .retryOn(IOException.class)
            .build();

    public boolean sendVerifyCode(String mobile, String code) {
        return Retries.inline()
                .policy(SMS_RETRY_POLICY)
                .call(() -> {
                    SmsResponse response = smsClient.send(mobile, code);
                    return response != null && response.isSuccess();
                });
    }
}
```

## 支付成功商户 Webhook 补偿 (MANAGED)

用户支付成功后，支付系统向商户服务器发送 Webhook。商户服务可能临时停机或网络异常：

- 前台执行 1 次，`foregroundMaxRetries=0`；
- 失败后进入后台，按指数抖动退避重试；
- `maxRetries=5` 表示总尝试上限为 6 次；
- 前台与后台共享 `attempts` 计数；
- 初始 intent 默认 5 分钟内留给前台；进程崩溃或未 handoff 时到期自动由 `RetryTaskWorker` 接管。

以下示例假设 Spring 容器已按[Spring 整合](retry-spring.md)提供 `ManagedRetryClient`，运行 Worker 的进程已注册 `MerchantWebhookRecoveryHandler`。

```java
import com.team4u.framework.retry.api.ManagedSubmitResult;
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.serializer.json.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MerchantNotifyService {

    @Autowired
    private ManagedRetryClient retryClient;

    @Autowired
    private MerchantWebhookClient webhookClient;

    private static final RetryPolicy NOTIFY_POLICY = RetryPolicy.builder()
            .maxRetries(5)
            .foregroundMaxRetries(0)
            .backoff(Backoffs.exponentialJitter(1000, 3.0, 30 * 60 * 1000L))
            .build();

    public void notifyMerchant(PaymentOrder order) {
        String payload = JsonUtil.toJsonStr(order);

        ManagedSubmitResult<Void> result = Retries.managed(retryClient)
                .taskType("merchant-webhook-notify")
                .idempotencyKey("NOTIFY|" + order.getOrderNo())
                .payload(payload)
                .policy(NOTIFY_POLICY)
                .call(() -> {
                    webhookClient.post(order.getNotifyUrl(), payload);
                    return null;
                });

        if (result.isCompleted()) {
            System.out.println("前台通知成功: " + order.getOrderNo());
        } else if (result.isAccepted()) {
            System.out.println("已交由后台补偿: " + order.getOrderNo());
        } else if (result.isFailed()) {
            Throwable error = ((ManagedSubmitResult.Failed<Void>) result).getError();
            System.err.println("通知终态失败: " + error.getMessage());
        }
    }
}
```

后台恢复处理器直接使用字符串 payload。真实业务目标操作和恢复处理器必须幂等，因为这是 at-least-once 交付边界。

```java
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import com.team4u.framework.serializer.json.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MerchantWebhookRecoveryHandler implements StringRecoveryHandler {

    @Autowired
    private MerchantWebhookClient webhookClient;

    @Override
    public String taskName() {
        return "merchant-webhook-notify";
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        PaymentOrder order = JsonUtil.toBean(payload, PaymentOrder.class);
        System.out.printf(
                "后台恢复商户通知: orderNo=%s, attempt=%d%n",
                order.getOrderNo(),
                context.getAttempt());
        webhookClient.post(order.getNotifyUrl(), payload);
    }
}
```

`context.getAttempt()` 是前后台连续后的当前尝试序号，从 1 开始。若 payload 反序列化或结果序列化发生基础设施异常，Worker 不会伪造业务 `FAILED`；任务保留租约状态，到期后由其他 Worker 凭 fencing 语义接管。

## Spring 声明式代理与上下文忽略

提现审核后调用外部银行打款通道，方法参数携带不可序列化的 `HttpServletRequest`：

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
            @RetryIgnore HttpServletRequest request
    ) {
        boolean success = thirdPartyBankClient.transfer(
                transferId, amountInCents, bankAccount);
        if (!success) {
            throw new IllegalStateException("银行返回处理中或网络超时");
        }
    }
}
```

后台回放时 `request` 为 `null`，业务逻辑不能读取它。`@Retryable` 的后台 task type 固定为 `ProxyInvocationReplay`，运行 `ManagedRetryRuntime` 的进程必须能通过 `BeanManager` 找到目标 Bean。
