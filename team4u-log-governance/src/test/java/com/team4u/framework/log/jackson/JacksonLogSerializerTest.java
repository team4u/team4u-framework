package com.team4u.framework.log.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.team4u.framework.log.config.FinOpsConfigRepository;
import com.team4u.framework.log.config.FinOpsConfigRepository.FinOpsConfig;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.mask.jackson.MaskConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.Collections;

public class JacksonLogSerializerTest {

    private JacksonLogSerializer serializer;
    private FinOpsConfigRepository repository;

    @Before
    public void setUp() {
        repository = FinOpsConfigRepository.getInstance();
        repository.stop();
        serializer = new JacksonLogSerializer();
    }

    @After
    public void tearDown() {
        repository.stop();
    }

    @Test
    public void serializesEventFieldsPayloadAndDuration() {
        LogEvent event = new LogEvent()
                .setAction("TestJson")
                .setLevel(Level.INFO)
                .setDurationMs(100);
        event.getPayload().put("key", "value");

        String json = serializer.serialize(event);
        Assert.assertTrue(json.contains("\"action\":\"TestJson\""));
        Assert.assertTrue(json.contains("\"durationMs\":100"));
        Assert.assertTrue(json.contains("\"payload\":{\"key\":\"value\"}"));
    }

    @Test
    public void truncatesPayloadStrings() {
        repository.replace(FinOpsConfig.defaults().withMaxStringLength(10));

        LogEvent event = new LogEvent().setAction("TestTruncation");
        event.getPayload().put("longString", "0123456789ABCDEF");

        String json = serializer.serialize(event);
        Assert.assertTrue(json.contains("\"longString\":\"0123456789"));
        Assert.assertFalse(json.contains("0123456789ABCDEF"));
    }

    @Test
    public void truncatesBeanStringFields() {
        repository.replace(FinOpsConfig.defaults().withMaxStringLength(10));

        String json = serializer.serialize(new LogEvent().setAction("VeryLongActionName"));
        Assert.assertTrue(json.contains("\"action\":\"VeryLongAc... [Truncated len:18]\""));
    }

    @Test
    public void serializesByteArraysAsSizeHintsInsteadOfBase64() {
        LogEvent event = new LogEvent().setAction("TestByteTruncation");
        event.getPayload().put("data", new byte[100]);

        Assert.assertTrue(serializer.serialize(event).contains("\"data\":\"[byte[] size: 100 bytes]\""));
    }

    @Test
    public void truncatesCompleteSerializedLog() {
        repository.replace(FinOpsConfig.defaults().withMaxLogLength(20));

        String json = serializer.serialize(new LogEvent().setAction("VeryLongActionNameThatWillBeTruncated"));
        Assert.assertEquals(20 + "... [Truncated at 20]".length(), json.length());
        Assert.assertTrue(json.endsWith("... [Truncated at 20]"));
    }

    @Test
    public void serializerAttributeSnapshotTakesPrecedenceOverRepository() throws Exception {
        Assert.assertTrue(
                serializeWithAttribute(5).contains(
                        "\"k\":\"12345... [Truncated len:9]\""));
    }

    @Test
    public void serializerFallsBackToRepositoryWhenAttributeIsMissing() throws Exception {
        repository.replace(FinOpsConfig.defaults().withMaxStringLength(6));
        Assert.assertTrue(
                serializeWithoutAttribute().contains(
                        "\"k\":\"123456... [Truncated len:9]\""));
    }

    @Test
    public void serializerUsesDefaultFallbackWhenRepositoryIsDefault() throws Exception {
        repository.replace(FinOpsConfig.defaults());
        String longText = String.join("", Collections.nCopies(2100, "a"));
        String json = serializeWithoutAttribute(longText);
        Assert.assertTrue(json.contains("... [Truncated len:2100]"));
        Assert.assertTrue(json.contains(String.join("", Collections.nCopies(2000, "a"))));
    }

    @Test
    public void serializationFailureReturnsEscapedFallback() {
        class Broken {
            public Object getError() {
                throw new RuntimeException("Forced serialization error");
            }
        }

        LogEvent event = new LogEvent().setAction("Error");
        event.getPayload().put("broken", new Broken());

        String json = serializer.serialize(event);
        Assert.assertTrue(json.contains("Serialization failed"));
    }

    @Test
    public void fallbackEscapesActionAndReason() {
        class Broken {
            public Object getBadValue() {
                throw new RuntimeException("line1\n\"quoted\"");
            }
        }

        LogEvent event = new LogEvent().setAction("bad\"action\n");
        event.getPayload().put("broken", new Broken());

        String json = serializer.serialize(event);
        Assert.assertTrue(json.contains("\\n"));
        Assert.assertTrue(json.contains("\\\"quoted\\\""));
        Assert.assertTrue(json.contains("bad\\\"action\\n"));
    }

    @Test
    public void resetRebuildsMapperAndKeepsGovernanceSerialization() {
        repository.replace(FinOpsConfig.defaults().withMaxStringLength(10));
        serializer.serialize(new LogEvent().setAction("before-reset"));

        serializer.reset();

        String json = serializer.serialize(new LogEvent().setAction("VeryLongActionName"));
        Assert.assertTrue(json.contains("\"action\":\"VeryLongAc... [Truncated len:18]\""));
    }

    private String serializeWithAttribute(int maxLength) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer());
        mapper.registerModule(module);

        return mapper.writer()
                .withAttribute(MaskConfig.ATTR_KEY, new MaskConfig().setMaxStringLength(maxLength))
                .writeValueAsString(Collections.singletonMap("k", "123456789"));
    }

    private String serializeWithoutAttribute() throws Exception {
        return serializeWithoutAttribute("123456789");
    }

    private String serializeWithoutAttribute(String value) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new TruncatingStringSerializer());
        mapper.registerModule(module);
        return mapper.writeValueAsString(Collections.singletonMap("k", value));
    }
}
