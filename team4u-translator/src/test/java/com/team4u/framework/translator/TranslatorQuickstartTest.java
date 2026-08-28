package com.team4u.framework.translator;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.translator.api.ResponseTranslator;
import com.team4u.framework.translator.engine.DefaultResponseTranslator;
import com.team4u.framework.translator.model.RawResponse;
import com.team4u.framework.translator.model.TranslatedResponse;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * End-to-end translator quickstart against a real router-core policy and JSON provider.
 */
public class TranslatorQuickstartTest {

    private static final String ROUTER_CONFIG = "{\"type\":\"expression\",\"rules\":["
            + "{\"condition\":\"domain == 'ORDER' && code == 'STOCK_ZERO'\","
            + "\"value\":{\"code\":\"PRODUCT_SOLD_OUT\",\"defaultMsg\":"
            + "\"No stock for ${action}: ${rawCode}/${rawMessage}\"}},"
            + "{\"condition\":\"code == 'DB_TIMEOUT'\","
            + "\"value\":{\"code\":\"SYSTEM_BUSY\",\"defaultMsg\":\"System busy (${rawCode})\"}},"
            + "{\"condition\":\"code == 'PARTIAL_MSG'\",\"value\":{\"defaultMsg\":"
            + "\"Partial failure: ${rawMessage}\"}},"
            + "{\"condition\":\"code == 'PARTIAL_CODE'\",\"value\":{\"code\":\"PARTIAL_DONE\"}}"
            + "]}";

    private TestConfigContext configContext;
    private ResponseTranslator translator;

    @Before
    public void setUp() {
        configContext = TestConfigContext.create();
        configContext.put("router.translator.quickstart", ROUTER_CONFIG);

        RoutingManager routingManager = RoutingManager.builder()
                .configManager(configContext.getConfigManager())
                .useGlobalInterceptors(false)
                .build();
        translator = new DefaultResponseTranslator(routingManager);
    }

    @After
    public void tearDown() {
        configContext.destroy();
    }

    @Test
    public void matchedRouteRendersTemplateArgsRawResponseAndTraceId() {
        Map<String, Object> args = new HashMap<>();
        args.put("action", "submit order");
        args.put("traceId", "trace-001");

        TranslatedResponse response = translator.translate(
                RawResponse.of("ORDER", "STOCK_ZERO", "no stock"),
                "translator.quickstart",
                args);

        Assert.assertEquals("PRODUCT_SOLD_OUT", response.getCode());
        Assert.assertEquals(
                "No stock for submit order: STOCK_ZERO/no stock",
                response.getMessage());
        Assert.assertEquals("trace-001", response.getTraceId());
    }

    @Test
    public void secondRuleUsesRawValuesWithoutBusinessArgs() {
        TranslatedResponse response = translator.translate(
                RawResponse.of("PAY", "DB_TIMEOUT", "database timeout"),
                "translator.quickstart",
                null);

        Assert.assertEquals("SYSTEM_BUSY", response.getCode());
        Assert.assertEquals("System busy (DB_TIMEOUT)", response.getMessage());
        Assert.assertNull(response.getTraceId());
    }

    @Test
    public void matchedDefinitionWithMissingFieldsFallsBackToRawResponse() {
        Map<String, Object> args = new HashMap<>();
        args.put("traceId", "trace-002");

        TranslatedResponse messageOnly = translator.translate(
                RawResponse.of("ORDER", "PARTIAL_MSG", "partial message"),
                "translator.quickstart",
                args);

        Assert.assertEquals("PARTIAL_MSG", messageOnly.getCode());
        Assert.assertEquals("Partial failure: partial message", messageOnly.getMessage());
        Assert.assertEquals("trace-002", messageOnly.getTraceId());

        TranslatedResponse codeOnly = translator.translate(
                RawResponse.of("ORDER", "PARTIAL_CODE", "partial code"),
                "translator.quickstart",
                args);

        Assert.assertEquals("PARTIAL_DONE", codeOnly.getCode());
        Assert.assertEquals("partial code", codeOnly.getMessage());
        Assert.assertEquals("trace-002", codeOnly.getTraceId());
    }

    @Test
    public void unmatchedRouteReturnsOriginalResponseAndTrace() {
        Map<String, Object> args = new HashMap<>();
        args.put("traceId", "trace-003");

        TranslatedResponse response = translator.translate(
                RawResponse.of("ORDER", "UNKNOWN", "keep original"),
                "translator.quickstart",
                args);

        Assert.assertEquals("UNKNOWN", response.getCode());
        Assert.assertEquals("keep original", response.getMessage());
        Assert.assertEquals("trace-003", response.getTraceId());
    }

    @Test
    public void missingRouteReturnsOriginalResponseAndTrace() {
        Map<String, Object> args = new HashMap<>();
        args.put("traceId", "trace-004");

        TranslatedResponse response = translator.translate(
                RawResponse.of("ORDER", "NO_ROUTE", "route absent"),
                "translator.absent",
                args);

        Assert.assertEquals("NO_ROUTE", response.getCode());
        Assert.assertEquals("route absent", response.getMessage());
        Assert.assertEquals("trace-004", response.getTraceId());
    }
}
