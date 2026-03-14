package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;
import com.team4u.framework.mask.config.MaskRuleRepository;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class JacksonMaskModuleTest {

    private final MaskRuleRepository repository = MaskRuleRepository.getInstance();
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        repository.reset();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JacksonMaskModule());
    }

    @After
    public void tearDown() {
        repository.reset();
    }

    @Test
    public void testNullMaskWritesJsonNull() throws Exception {
        String json = objectMapper.writeValueAsString(new NullMaskedPayload());
        Assert.assertEquals("{\"secret\":null}", json);
    }

    @Test
    public void testMaskAnnotationOnNonStringFieldKeepsOriginalType() throws Exception {
        String json = objectMapper.writeValueAsString(new NonStringAnnotatedPayload());
        Assert.assertEquals("{\"mobile\":13812345678}", json);
    }

    @Test
    public void testExternalRuleOnNonStringFieldKeepsOriginalType() throws Exception {
        Map<String, Map<String, String>> rules = new LinkedHashMap<>();
        Map<String, String> fieldRules = new LinkedHashMap<>();
        fieldRules.put("mobile", MaskType.MOBILE.name());
        rules.put(ExternalRulePayload.class.getName(), fieldRules);
        repository.setRuleCache(rules);

        String json = objectMapper.writeValueAsString(new ExternalRulePayload());
        Assert.assertEquals("{\"mobile\":13812345678}", json);
    }

    @Test
    public void testMapMaskingPreservesContentInclusion() throws Exception {
        Map<String, Map<String, String>> rules = new LinkedHashMap<>();
        Map<String, String> fieldRules = new LinkedHashMap<>();
        fieldRules.put("token", MaskType.NULL.name());
        rules.put(LinkedHashMap.class.getName(), fieldRules);
        repository.setRuleCache(rules);

        MapHolder payload = new MapHolder();
        payload.data.put("token", "secret");
        payload.data.put("other", "visible");

        String json = objectMapper.writeValueAsString(payload);
        Assert.assertEquals("{\"data\":{\"other\":\"visible\"}}", json);
    }

    @Test
    public void testMapMaskingPreservesContextualContentSerializer() throws Exception {
        Map<String, Map<String, String>> rules = new LinkedHashMap<>();
        Map<String, String> fieldRules = new LinkedHashMap<>();
        fieldRules.put("email", MaskType.EMAIL.name());
        rules.put(LinkedHashMap.class.getName(), fieldRules);
        repository.setRuleCache(rules);

        ContextualMapHolder payload = new ContextualMapHolder();
        payload.data.put("email", "jay.wuy@gmail.com");

        String json = objectMapper.writeValueAsString(payload);
        Assert.assertEquals("{\"data\":{\"email\":\"wrapped:j****@gmail.com\"}}", json);
    }

    private static class NullMaskedPayload {
        @Mask(MaskType.NULL)
        public final String secret = "12345";
    }

    private static class NonStringAnnotatedPayload {
        @Mask(MaskType.MOBILE)
        public final Long mobile = 13812345678L;
    }

    private static class ExternalRulePayload {
        public final Long mobile = 13812345678L;
    }

    private static class MapHolder {
        @JsonInclude(content = JsonInclude.Include.NON_NULL)
        public final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    }

    private static class ContextualMapHolder {
        @JsonSerialize(contentUsing = PrefixingStringSerializer.class)
        public final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    }

    private static class PrefixingStringSerializer extends StdSerializer<String> {
        private PrefixingStringSerializer() {
            super(String.class);
        }

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString("wrapped:" + value);
        }
    }
}
