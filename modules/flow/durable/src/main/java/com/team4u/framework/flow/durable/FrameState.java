package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.StopReason;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 快照中的帧执行状态与不可变帧栈。
 *
 * @author jay.wu
 */
public final class FrameState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 帧执行阶段。
     */
    public enum Phase {
        BODY,
        RECOVER,
        ENSURE,
        COMPLETED
    }

    /**
     * 单个不可变执行帧。
     */
    public static final class ExecutionFrame implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String sequenceAddress;
        private final int cursor;
        private final String scopeInputSlot;
        private final String selectedBranch;
        private final Phase phase;
        private final DurableFailure pendingFailure;
        private final StopReason pendingStopReason;
        private final String pathPrefix;

        public ExecutionFrame(String sequenceAddress,
                              int cursor,
                              String scopeInputSlot,
                              String selectedBranch,
                              Phase phase,
                              DurableFailure pendingFailure,
                              StopReason pendingStopReason,
                              String pathPrefix) {
            if (sequenceAddress == null || sequenceAddress.trim().isEmpty()) {
                throw new IllegalArgumentException("sequenceAddress must not be null or blank");
            }
            if (cursor < 0) {
                throw new IllegalArgumentException("cursor must be non-negative, got: " + cursor);
            }
            if (scopeInputSlot == null || scopeInputSlot.trim().isEmpty()) {
                throw new IllegalArgumentException("scopeInputSlot must not be null or blank");
            }
            this.phase = Objects.requireNonNull(phase, "phase must not be null");

            // Strengthen frame phase invariants
            if (this.phase == Phase.BODY) {
                if (pendingFailure != null || pendingStopReason != null) {
                    throw new IllegalArgumentException("BODY frame must not carry pendingFailure or pendingStopReason");
                }
            } else if (this.phase == Phase.RECOVER) {
                if (pendingFailure == null) {
                    throw new IllegalArgumentException("RECOVER frame requires pendingFailure");
                }
                if (pendingStopReason != null) {
                    throw new IllegalArgumentException("RECOVER frame must not carry pendingStopReason");
                }
            } else if (this.phase == Phase.ENSURE) {
                if (pendingFailure != null && pendingStopReason != null) {
                    throw new IllegalArgumentException("ENSURE frame cannot carry both pendingFailure and pendingStopReason");
                }
            } else if (this.phase == Phase.COMPLETED) {
                if (pendingFailure != null || pendingStopReason != null) {
                    throw new IllegalArgumentException("COMPLETED frame must not carry pendingFailure or pendingStopReason");
                }
            }

            this.sequenceAddress = sequenceAddress;
            this.cursor = cursor;
            this.scopeInputSlot = scopeInputSlot;
            this.selectedBranch = selectedBranch;
            this.pendingFailure = pendingFailure;
            this.pendingStopReason = pendingStopReason;
            this.pathPrefix = pathPrefix != null ? pathPrefix : "";
        }

        public ExecutionFrame(String sequenceAddress,
                              int cursor,
                              String scopeInputSlot,
                              String selectedBranch,
                              Phase phase,
                              DurableFailure pendingFailure,
                              StopReason pendingStopReason) {
            this(sequenceAddress, cursor, scopeInputSlot, selectedBranch, phase, pendingFailure, pendingStopReason, "");
        }

        public static ExecutionFrame initial(String sequenceAddress) {
            return new ExecutionFrame(sequenceAddress, 0, "input", null, Phase.BODY, null, null, "");
        }

        public static ExecutionFrame initial(String sequenceAddress, String pathPrefix) {
            return new ExecutionFrame(sequenceAddress, 0, "input", null, Phase.BODY, null, null, pathPrefix);
        }

        public String sequenceAddress() {
            return sequenceAddress;
        }

        public int cursor() {
            return cursor;
        }

        public String scopeInputSlot() {
            return scopeInputSlot;
        }

        public String selectedBranch() {
            return selectedBranch;
        }

        public Phase phase() {
            return phase;
        }

        public DurableFailure pendingFailure() {
            return pendingFailure;
        }

        public StopReason pendingStopReason() {
            return pendingStopReason;
        }

        public String pathPrefix() {
            return pathPrefix != null ? pathPrefix : "";
        }

        public ExecutionFrame withCursor(int newCursor) {
            return new ExecutionFrame(sequenceAddress, newCursor, scopeInputSlot, selectedBranch, phase, pendingFailure, pendingStopReason, pathPrefix);
        }

        public ExecutionFrame withSelectedBranch(String newSelectedBranch) {
            return new ExecutionFrame(sequenceAddress, cursor, scopeInputSlot, newSelectedBranch, phase, pendingFailure, pendingStopReason, pathPrefix);
        }

        public ExecutionFrame withPhase(Phase newPhase) {
            return new ExecutionFrame(sequenceAddress, cursor, scopeInputSlot, selectedBranch, newPhase, pendingFailure, pendingStopReason, pathPrefix);
        }

        public ExecutionFrame withRecoverPhase(DurableFailure failure) {
            return new ExecutionFrame(sequenceAddress, cursor, scopeInputSlot, selectedBranch, Phase.RECOVER, Objects.requireNonNull(failure, "failure must not be null"), null, pathPrefix);
        }

        public ExecutionFrame withEnsurePhase(DurableFailure failure, StopReason stopReason) {
            return new ExecutionFrame(sequenceAddress, cursor, scopeInputSlot, selectedBranch, Phase.ENSURE, failure, stopReason, pathPrefix);
        }

        public ExecutionFrame withPathPrefix(String newPathPrefix) {
            return new ExecutionFrame(sequenceAddress, cursor, scopeInputSlot, selectedBranch, phase, pendingFailure, pendingStopReason, newPathPrefix);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ExecutionFrame that = (ExecutionFrame) o;
            return cursor == that.cursor &&
                    Objects.equals(sequenceAddress, that.sequenceAddress) &&
                    Objects.equals(scopeInputSlot, that.scopeInputSlot) &&
                    Objects.equals(selectedBranch, that.selectedBranch) &&
                    phase == that.phase &&
                    Objects.equals(pendingFailure, that.pendingFailure) &&
                    Objects.equals(pendingStopReason, that.pendingStopReason) &&
                    Objects.equals(pathPrefix, that.pathPrefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sequenceAddress, cursor, scopeInputSlot, selectedBranch, phase, pendingFailure, pendingStopReason, pathPrefix);
        }

        @Override
        public String toString() {
            return "ExecutionFrame{" + sequenceAddress + "@" + cursor + " [" + phase + "]" +
                    (pathPrefix != null && !pathPrefix.isEmpty() ? ", prefix=" + pathPrefix : "") +
                    (selectedBranch != null ? ", branch=" + selectedBranch : "") +
                    (pendingFailure != null ? ", failure=" + pendingFailure : "") +
                    (pendingStopReason != null ? ", stop=" + pendingStopReason : "") + '}';
        }
    }

    private final List<ExecutionFrame> frames;

    public FrameState(List<ExecutionFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must not be null or empty");
        }
        for (ExecutionFrame frame : frames) {
            Objects.requireNonNull(frame, "ExecutionFrame in frames must not be null");
        }
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
    }

    public static FrameState initial() {
        return new FrameState(Collections.singletonList(ExecutionFrame.initial("/")));
    }

    public static FrameState initial(String sequenceAddress) {
        return new FrameState(Collections.singletonList(ExecutionFrame.initial(sequenceAddress)));
    }

    public static FrameState initial(String sequenceAddress, String pathPrefix) {
        return new FrameState(Collections.singletonList(ExecutionFrame.initial(sequenceAddress, pathPrefix)));
    }

    public List<ExecutionFrame> frames() {
        return frames;
    }

    public ExecutionFrame topFrame() {
        return frames.get(frames.size() - 1);
    }

    public int cursor() {
        return topFrame().cursor();
    }

    public String activeScope() {
        return topFrame().sequenceAddress();
    }

    public boolean isRecoverPhase() {
        return topFrame().phase() == Phase.RECOVER;
    }

    public FrameState withCursor(int newCursor) {
        return replaceTopFrame(topFrame().withCursor(newCursor));
    }

    public FrameState pushFrame(ExecutionFrame frame) {
        Objects.requireNonNull(frame, "ExecutionFrame must not be null");
        List<ExecutionFrame> newFrames = new ArrayList<>(frames);
        newFrames.add(frame);
        return new FrameState(newFrames);
    }

    public FrameState popFrame() {
        if (frames.size() <= 1) {
            throw new IllegalStateException("Cannot pop the root frame from FrameState");
        }
        List<ExecutionFrame> newFrames = new ArrayList<>(frames.subList(0, frames.size() - 1));
        return new FrameState(newFrames);
    }

    public FrameState replaceTopFrame(ExecutionFrame newTop) {
        Objects.requireNonNull(newTop, "ExecutionFrame must not be null");
        List<ExecutionFrame> newFrames = new ArrayList<>(frames);
        newFrames.set(newFrames.size() - 1, newTop);
        return new FrameState(newFrames);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FrameState that = (FrameState) o;
        return Objects.equals(frames, that.frames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frames);
    }

    @Override
    public String toString() {
        return "FrameState{stack=" + frames + '}';
    }
}
