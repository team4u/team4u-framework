# 实战案例

本章介绍 `team4u-mask` 在 HTTP API 出参保护、第三方回调报文动态治理与网关日志安全打印中的典型生产实践。

---

## Spring Boot 开放平台出参自动脱敏

### 业务场景
开放平台用户详情接口对外返回数据，要求：
- 真实姓名脱敏；
- 手机号、身份证号、银行卡号脱敏；
- 密码等绝密字段掩码；
- Controller 层直接返回业务 VO，序列化自动完成脱敏，业务代码零侵入。

### 代码实现

#### 响应 VO 声明
```java
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;
import lombok.Data;

@Data
public class UserDetailVO {
    private Long userId;

    @Mask(MaskType.NAME)
    private String realName;

    @Mask(MaskType.MOBILE)
    private String mobilePhone;

    @Mask(MaskType.ID_CARD_NO)
    private String idCard;

    @Mask(MaskType.BANK_CARD_NO)
    private String bankCardNo;

    @Mask(MaskType.ADDRESS)
    private String homeAddress;
}
```

#### 配置 JacksonMaskModule 与 Controller
```java
@Configuration
public class WebJacksonConfig {
    @Bean
    public JacksonMaskModule jacksonMaskModule() {
        return new JacksonMaskModule();
    }
}

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/{userId}")
    public UserDetailVO getUserDetail(@PathVariable Long userId) {
        // 直接返回包含真实数据的实体，Jackson 序列化时自动按规则脱敏
        return userService.queryUserDetail(userId);
    }
}
```

---

## 第三方支付回调 Map 报文动态治理

### 业务场景
第三方支付平台异步回调通知入参为一个动态 `Map<String, Object>`，其中包含敏感字段 `payerPhone`、`bankAccount` 与 `authCode`。由于没有源码实体类，通过配置中心下发动态规则进行无侵入脱敏。

### 配置中心规则 (`team4u.mask.rules`)
```json
{
  "java.util.HashMap": {
    "payerPhone": "MOBILE",
    "bankAccount": "BANK_CARD_NO",
    "authCode": "HIDE"
  }
}
```

### 启动规则监听并在回调中安全打印日志
```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.mask.MaskBootstrap;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/callback")
public class PaymentCallbackController {

    @PostConstruct
    public void init() {
        // 绑定配置中心并启动动态规则监听
        MaskBootstrap.global().start(ConfigManager.global());
    }

    @PostMapping("/notify")
    public String handlePayNotify(@RequestBody Map<String, Object> notifyParams) {
        // Jackson 序列化此 Map 时自动按规则对 payerPhone 与 bankAccount 脱敏
        log.info("收到支付异步通知: {}", JsonUtil.toJsonStr(notifyParams));
        
        // 业务层仍可直接从 Map 中获取真实数据执行验签与入账
        String realPhone = (String) notifyParams.get("payerPhone");
        
        return "SUCCESS";
    }
}
```

---

## API 网关访问日志与超长报文防打爆

### 业务场景
在 API 网关或服务拦截器中记录请求与响应报文。部分接口入参可能包含大段 Base64 编码的图片。要求：
1. 自动对敏感字段脱敏；
2. 对超长字符串限制最多输出 64 字符，防止打满磁盘日志。

### 代码实现

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.mask.jackson.JacksonMaskModule;
import com.team4u.framework.mask.jackson.MaskConfig;
import lombok.extern.slf4j.Slf4j;
import javax.servlet.http.HttpServletRequest;

@Slf4j
public class GatewayAccessLogFilter {

    private final ObjectMapper mapper;
    private final MaskConfig logMaskConfig;

    public GatewayAccessLogFilter() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JacksonMaskModule());
        // 限制字符串最大 64 字符
        this.logMaskConfig = new MaskConfig().setMaxStringLength(64);
    }

    public void logResponseBody(String traceId, Object responseBody) {
        try {
            // 通过 withAttribute 传入截断配置
            String safeJson = mapper.writer()
                    .withAttribute(MaskConfig.ATTR_KEY, logMaskConfig)
                    .writeValueAsString(responseBody);

            log.info("[API-ACCESS-LOG] traceId={}, response={}", traceId, safeJson);
        } catch (Exception e) {
            log.warn("序列化日志失败", e);
        }
    }
}
```
