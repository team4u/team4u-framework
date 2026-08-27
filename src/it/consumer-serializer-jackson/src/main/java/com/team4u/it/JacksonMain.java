package com.team4u.it;

import com.team4u.framework.serializer.json.JsonUtil;

import java.util.Objects;

public class JacksonMain {

    public static void main(String[] args) {
        Payload output = JsonUtil.toBean(JsonUtil.toJsonStr(new Payload("Team4u", 1)), Payload.class);
        if (output == null || !"Team4u".equals(output.getName()) || output.getVersion() != 1) {
            throw new IllegalStateException("Jackson roundtrip failed: " + output);
        }
        System.out.println(output.getName() + ":" + output.getVersion());
    }

    public static class Payload {
        private String name;
        private int version;

        public Payload() {
        }

        public Payload(String name, int version) {
            this.name = name;
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Payload)) {
                return false;
            }
            Payload payload = (Payload) other;
            return version == payload.version && Objects.equals(name, payload.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, version);
        }
    }
}
