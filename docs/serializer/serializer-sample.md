# 实战案例

本章介绍 `team4u-serializer` 在 SDK 依赖解耦、复杂泛型报文解析与动态容错读取中的典型实战。

---

## 案例 1：通用 SDK 中的 JSON 编解码解耦

### 业务场景
开发一个内部通用的 HTTP 远程调用 SDK，内部需要对请求和响应进行序列化。为了避免在 SDK 中写死具体的 JSON 框架导致与各个微服务宿主工程产生版本冲突，SDK 内部统一基于 `team4u-serializer-json` 提供的 `JsonUtil` 进行操作。

### 代码实现
```java
import com.team4u.framework.serializer.json.JsonUtil;

public class GenericHttpClient {

    public <T> T executePost(String url, Object requestPayload, Class<T> responseType) {
        // 1. 序列化请求体
        String jsonBody = JsonUtil.toJsonStr(requestPayload);

        // 2. 发起 HTTP POST 请求
        String rawResponse = sendHttp(url, jsonBody);

        // 3. 反序列化响应体
        return JsonUtil.toBean(rawResponse, responseType);
    }

    private String sendHttp(String url, String json) {
        // HTTP 发送逻辑
        return "{\"code\":200,\"message\":\"OK\"}";
    }
}
```

---

## 案例 2：复杂嵌套泛型报文解析

### 业务场景
API 网关返回通用的统一响应包装格式：`ApiResponse<PageResult<TradeOrderDTO>>`。要求在不丢失内部嵌套泛型类型信息的前提下完整反序列化。

### 代码实现
```java
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.serializer.json.JsonUtil;
import java.util.List;

public class ApiConsumer {

    public static class ApiResponse<T> {
        private int code;
        private T data;
        public int getCode() { return code; }
        public T getData() { return data; }
    }

    public static class PageResult<T> {
        private int total;
        private List<T> list;
        public int getTotal() { return total; }
        public List<T> getList() { return list; }
    }

    public static class TradeOrderDTO {
        private String orderId;
        private Double amount;
        public String getOrderId() { return orderId; }
        public Double getAmount() { return amount; }
    }

    public static void main(String[] args) {
        String json = "{\"code\":200,\"data\":{\"total\":1,\"list\":[{\"orderId\":\"ORD-9988\",\"amount\":199.9}]}}";

        // 通过 TypeReference 捕获 3 层泛型
        ApiResponse<PageResult<TradeOrderDTO>> response = JsonUtil.toBean(
                json,
                new TypeReference<ApiResponse<PageResult<TradeOrderDTO>>>() {}
        );

        System.out.println("Code: " + response.getCode());
        System.out.println("Total: " + response.getData().getTotal());
        System.out.println("OrderId: " + response.getData().getList().get(0).getOrderId());
    }
}
```

---

## 案例 3：配置中心可选扩展配置的容错解析 (`ignoreError`)

### 业务场景
配置中心下发动态插件配置，部分历史配置由于格式错误或版本不兼容可能解析失败。要求：解析失败时不阻断主流程启动，而是安全返回 `null` 并启用默认策略。

### 代码实现
```java
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.serializer.json.JsonUtil;
import java.util.Map;

public class PluginConfigLoader {

    public Map<String, Object> loadPluginConfig(String rawConfigJson) {
        // 使用 ignoreError = true 进行容错反序列化
        Map<String, Object> config = JsonUtil.toBean(
                rawConfigJson,
                new TypeReference<Map<String, Object>>() {},
                true // 忽略反序列化错误
        );

        if (config == null) {
            System.out.println("配置解析失败或为空，启用默认内置配置");
            return Map.of("timeout", 3000, "retry", 3);
        }

        return config;
    }
}
```
