package com.team4u.framework.flow.durable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快照中的帧执行状态。
 *
 * @author jay.wu
 */
public final class FrameState {

    private final int cursor;
    private final String activeScope;
    private final Map<String, String> branchChoices;
    private final boolean recoverPhase;

    public FrameState(int cursor, String activeScope, Map<String, String> branchChoices, boolean recoverPhase) {
        this.cursor = cursor;
        this.activeScope = activeScope != null ? activeScope : "";
        this.branchChoices = branchChoices != null ? Collections.unmodifiableMap(new LinkedHashMap<>(branchChoices)) : Collections.emptyMap();
        this.recoverPhase = recoverPhase;
    }

    public static FrameState initial() {
        return new FrameState(0, "", Collections.emptyMap(), false);
    }

    public int cursor() {
        return cursor;
    }

    public String activeScope() {
        return activeScope;
    }

    public Map<String, String> branchChoices() {
        return branchChoices;
    }

    public String branchChoice(String nodeAddress) {
        return branchChoices.get(nodeAddress);
    }

    public boolean isRecoverPhase() {
        return recoverPhase;
    }

    public FrameState withCursor(int newCursor) {
        return new FrameState(newCursor, activeScope, branchChoices, recoverPhase);
    }

    public FrameState withBranchChoice(String nodeAddress, String branchKey) {
        Map<String, String> newChoices = new LinkedHashMap<>(branchChoices);
        newChoices.put(nodeAddress, branchKey);
        return new FrameState(cursor, activeScope, newChoices, recoverPhase);
    }

    public FrameState withRecoverPhase(boolean newRecoverPhase) {
        return new FrameState(cursor, activeScope, branchChoices, newRecoverPhase);
    }
}
