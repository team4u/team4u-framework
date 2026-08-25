# 实战案例

本章介绍 `team4u-retry` 在外部 API 即时容灾、支付通知可靠补偿及 Spring 声明式代理中的典型实战范例。

---

## 案例 1：第三方短信发送即时容灾 (INLINE 模式)

### 业务场景
用户注册时发送验证码短信。当下游短信通道由于网络闪断发生超时时，系统立即在当前线程重试最多 2 次（连同首次共 3 次），采用指数退避：

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
                    SmsResponse resp = smsClient.send(mobile, code);
                    return resp != null && resp.isSuccess();
                });
    }
}
```

---

## 案例 2：支付成功商户 Webhook 通知可靠补偿 (MANAGED 模式)

### 业务场景
用户支付成功后，支付系统需要向商户服务器发送 Webhook 通知。商户服务可能临时停机维护或网络异常：
- 前台即时尝试 1 次（`foregroundMaxRetries = 0`），若商户正常则秒级响应；
- 若商户未响应，任务自动转入后台持久化接管，按指数抖动退避重试最多 5 次；
- 服务发版重启后未完成的通知任务由后台 `RetryLeaseWorker` 自动接管继续重试。

### 1. 提交任务
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

    private static final RetryPolicy NOTIFY_POLICY = RetryPolicy.builder()
            .maxRetries(5)
            .foregroundMaxRetries(0) // 前台仅尝试 1 次首次执行，失败立即交后台
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
                    httpPostToMerchant(order.getNotifyUrl(), payload);
                    return null;
                });

        if (result.isCompleted()) {
            System.out.println("前台通知商户即时成功: " + order.getOrderNo());
        } else if (result.isAccepted()) {
            System.out.println("前台未成功，已交由后台持续补偿重试: " + order.getOrderNo());
        }
    }

    private void httpPostToMerchant(String url, String json) {
        // HTTP POST 发送逻辑，若非 200 则抛出异常
    }
}
```

### 2. 后台恢复处理
```java
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.runtime.lease.StringRecoveryHandler;
import com.team4u.framework.serializer.json.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MerchantNotifyRecoveryHandler implements StringRecoveryHandler {

    @Autowired
    private MerchantNotifyService notifyService;

    @Override
    public String taskName() {
        return "merchant-webhook-notify";
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        PaymentOrder order = JsonUtil.toBean(payload, PaymentOrder.class);
        System.out.printf("后台 Worker 恢复重试商户通知: orderNo=%s, attempt=%d%n",
                order.getOrderNo(),
                context.getAttemptCount());

        // 执行重试发送
        notifyService.notifyMerchant(order);
    }
}
```

---

## 案例 3：Spring 声明式注解与上下文忽略 (@Retryable + @RetryIgnore)

### 业务场景
在处理用户提现审核后，系统调用外部银行打款通道，方法参数中携带了不可序列化的 `HttpServletRequest` 请求上下文：

```java
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;
import com.team4u.framework.retry.proxy.serialize.RetryIgnore;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

@Service
public class BankTransferService {

    // 声明托管重试模式，返回类型为 void
    @Retryable(policy = "bank-transfer-policy", mode = RetryMode.MANAGED)
    public void transferMoney(
            String transferId,
            Long amountInCents,
            String bankAccount,
            @RetryIgnore HttpServletRequest request // 忽略不可序列化参数，不影响幂等键计算
    ) {
        // 调用第三方银行转账接口
        boolean success = thirdPartyBankClient.transfer(transferId, amountInCents, bankAccount);
        if (!success) {
            throw new RuntimeException("银行返回转账处理中或网络超时");
        }
    }
}
```

