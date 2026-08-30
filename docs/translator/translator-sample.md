# 实战案例

本章介绍 `team4u-translator` 在开放网关全局异常拦截、多支付渠道统一映射与 RPC 统一翻译中的实战落地。

---

## Spring Boot 全局 ControllerAdvice 统一异常拦截

### 业务场景
在微服务对外 API 网关中，捕获所有上游服务抛出的 `BizRpcException`，动态翻译为面向前端 App 的统一规范 JSON 响应，并自动透传链路追踪 `traceId`。

### 代码实现
```java
package com.mycompany.gateway.handler;

import com.mycompany.common.exception.BizRpcException;
import com.mycompany.common.model.ResultVO;
import com.team4u.framework.translator.api.ResponseTranslator;
import com.team4u.framework.translator.model.RawResponse;
import com.team4u.framework.translator.model.TranslatedResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @Autowired
    private ResponseTranslator translator;

    @ExceptionHandler(BizRpcException.class)
    public ResultVO<Void> handleRpcException(BizRpcException ex, HttpServletRequest request) {
        // 1. 组装上游原始响应（携带底层异常堆栈）
        RawResponse raw = new RawResponse(
                ex.getDomain(),
                ex.getErrorCode(),
                ex.getMessage(),
                ex
        );

        // 2. 传递业务上下文参数
        Map<String, Object> args = new HashMap<>();
        args.put("traceId", MDC.get("traceId"));
        args.put("uri", request.getRequestURI());
        args.put("action", request.getMethod() + " " + request.getRequestURI());

        // 3. 执行翻译（指定网关错误路由 ID）
        TranslatedResponse response = translator.translate(raw, "gateway.error.router", args);

        // 4. 返回对外规范模型
        return ResultVO.fail(
                response.getCode(),
                response.getMessage(),
                response.getTraceId()
        );
    }
}
```

---

## 聚合支付多渠道原始错误代码统一映射

### 业务场景
聚合支付网关对接了微信支付、支付宝、云闪付等多家渠道。各渠道对“账户余额不足”返回的原始状态码各异：
- 微信支付：`domain = "WECHAT", code = "NOTENOUGH"
- 支付宝：`domain = "ALIPAY", code = "ACQ.BUYER_BALANCE_NOT_ENOUGH"
- 银联：`domain = "UNIONPAY", code = "51"

系统需将各渠道错误统一归一化为标准的 `PAY_BALANCE_INSUFFICIENT`，并向用户提示友好的文案。

### 路由规则配置 (`router.pay-channel-error`)
```json
{
  "id": "pay-channel-error",
  "type": "expression",
  "rules": [
    {
      "condition": "domain == 'WECHAT' && code == 'NOTENOUGH'",
      "value": {
        "code": "PAY_BALANCE_INSUFFICIENT",
        "defaultMsg": "您的微信支付零钱不足，请切换支付方式"
      }
    },
    {
      "condition": "domain == 'ALIPAY' && code == 'ACQ.BUYER_BALANCE_NOT_ENOUGH'",
      "value": {
        "code": "PAY_BALANCE_INSUFFICIENT",
        "defaultMsg": "您的支付宝账户余额不足，请切换支付方式"
      }
    },
    {
      "condition": "domain == 'UNIONPAY' && code == '51'",
      "value": {
        "code": "PAY_BALANCE_INSUFFICIENT",
        "defaultMsg": "您的银行卡余额不足，请充值后重试"
      }
    },
    {
      "condition": "code in ['NETWORK_ERROR', 'CHANNEL_MAINTENANCE']",
      "value": {
        "code": "PAY_CHANNEL_UNAVAILABLE",
        "defaultMsg": "支付通道维护中[${rawCode}]，请选择其他支付方式"
      }
    }
  ],
  "fallbackValue": {
    "code": "PAY_FAILED",
    "defaultMsg": "支付失败：${rawMessage}"
  }
}
```

### 业务调用
```java
public class PaymentCallbackHandler {

    private final ResponseTranslator translator = new DefaultResponseTranslator();

    public PaymentResult handleChannelResponse(String channel, String channelCode, String channelMsg) {
        RawResponse raw = RawResponse.of(channel, channelCode, channelMsg);
        
        TranslatedResponse response = translator.translate(raw, "pay-channel-error", null);
        
        return new PaymentResult(response.getCode(), response.getMessage());
    }
}
```
