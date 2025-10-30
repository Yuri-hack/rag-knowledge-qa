package io.github.yuri_hack.rag_knowledge_qa.enums;

import java.util.Optional;

public enum IntentEnum {
    KNOWLEDGE_BASE("knowledge_base", 0),
    DAILY_CHAT("daily_chat", 1);

    private final String desc;
    private final int code;

    IntentEnum(String desc, int code) {
        this.desc = desc;
        this.code = code;
    }

    public String getDesc() {
        return desc;
    }

    public int getCode() {
        return code;
    }

    public static Optional<IntentEnum> getByDesc(String desc) {
        for (IntentEnum intentEnum : IntentEnum.values()) {
            if (intentEnum.getDesc().equals(desc)) {
                return Optional.of(intentEnum);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "IntentEnum{" +
                "desc='" + desc + '\'' +
                ", code=" + code +
                '}';
    }
}
