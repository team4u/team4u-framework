package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Failure;
import com.team4u.framework.flow.NodeDescriptor;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.Reason;
import com.team4u.framework.flow.Recovery;
import com.team4u.framework.flow.Resumed;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Encodes runtime state as framework metadata plus opaque application slots. */
final class SnapshotCodec {
    private static final int MAGIC = 0x54344644;
    private static final int METADATA_VERSION = 1;
    private static final int MAX_COUNT = 1_000_000;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;

    static final class Payload {
        private final byte[] metadata;
        private final Map<String, StoredValue> slots;

        Payload(byte[] metadata, Map<String, StoredValue> slots) {
            this.metadata = metadata;
            this.slots = slots;
        }

        byte[] metadata() { return metadata; }
        Map<String, StoredValue> slots() { return slots; }
    }

    private SnapshotCodec() { }

    static Payload encode(DurableState.MachineState state, StateMapper mapper,
                          Set<String> knownRoles) {
        Objects.requireNonNull(state, "state must not be null");
        try {
            Encoder encoder = new Encoder(mapper, knownRoles);
            encoder.output.writeInt(MAGIC);
            encoder.output.writeInt(METADATA_VERSION);
            encoder.output.writeInt(state.frames.size());
            for (DurableState.RuntimeFrame frame : state.frames) {
                encoder.frame(frame);
            }
            encoder.outcome(state.outcome);
            // pending resume 信号仅存于信封（pendingResume + resume:<name> 槽），
            // 不进入帧元数据：resume 的独立提交无需重编码帧栈。
            encoder.output.flush();
            return new Payload(encoder.bytes.toByteArray(),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, StoredValue>(encoder.slots)));
        } catch (DurableException error) {
            throw error;
        } catch (IOException error) {
            throw codec("Cannot encode framework metadata", error);
        } catch (RuntimeException error) {
            throw frameMismatch("Cannot encode runtime state", error);
        }
    }

    static DurableState.MachineState decode(DurableSnapshot snapshot,
                                            StateMapper mapper,
                                            DurablePlanCompiler.Definition definition) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        try {
            Decoder decoder = new Decoder(snapshot.frameMetadata(), snapshot.slots(),
                    mapper, definition);
            if (decoder.input.readInt() != MAGIC
                    || decoder.input.readInt() != METADATA_VERSION) {
                throw frameMismatch("Unsupported framework metadata format");
            }
            int count = decoder.count("frame count");
            ArrayList<DurableState.RuntimeFrame> frames =
                    new ArrayList<DurableState.RuntimeFrame>(count);
            for (int i = 0; i < count; i++) frames.add(decoder.frame());
            DurableState.MachineOutcome outcome = decoder.outcome();
            if (decoder.input.available() != 0) {
                throw frameMismatch("Trailing framework metadata");
            }
            // pending resume 信号从信封重建：pendingResume 要求 resume:<awaitingPoint> 槽存在
            Object pendingSignal = null;
            if (snapshot.pendingResume()) {
                if (snapshot.awaitingPoint() == null) {
                    throw frameMismatch("Pending resume envelope lacks an awaiting point");
                }
                String role = DurablePlanCompiler.resumeRole(snapshot.awaitingPoint());
                if (!definition.slotRoles().contains(role)) {
                    throw frameMismatch("Unknown resume point in envelope: "
                            + snapshot.awaitingPoint());
                }
                pendingSignal = decoder.resumeSignal(role);
            }
            if (!decoder.usedRoles.equals(snapshot.slots().keySet())) {
                throw codec("Snapshot contains missing or unreferenced state slots", null);
            }
            DurableState.MachineState state = new DurableState.MachineState(
                    snapshot.executionId(), frames);
            state.lifecycle = snapshot.lifecycle();
            state.outcome = outcome;
            state.awaitingPoint = snapshot.awaitingPoint();
            state.pendingSignal = pendingSignal;
            RestoredStateValidator.validate(definition, state);
            return state;
        } catch (DurableException error) {
            throw error;
        } catch (EOFException error) {
            throw frameMismatch("Truncated framework metadata", error);
        } catch (IOException error) {
            throw frameMismatch("Cannot decode framework metadata", error);
        } catch (RuntimeException error) {
            throw frameMismatch("Invalid restored runtime state", error);
        }
    }

    static StoredValue encodeUser(StateMapper mapper, String role, Object value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return Objects.requireNonNull(mapper.encode(value),
                    "state mapper returned null StoredValue");
        } catch (DurableException error) {
            throw error;
        } catch (Exception error) {
            throw codec("Cannot encode state slot " + role, error);
        }
    }

    private static final class Encoder {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream output = new DataOutputStream(bytes);
        private final LinkedHashMap<String, StoredValue> slots =
                new LinkedHashMap<String, StoredValue>();
        private final LinkedHashMap<String, Object> valuesByRole =
                new LinkedHashMap<String, Object>();
        private final StateMapper mapper;
        private final Set<String> knownRoles;

        Encoder(StateMapper mapper, Set<String> knownRoles) {
            this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
            this.knownRoles = Objects.requireNonNull(knownRoles,
                    "knownRoles must not be null");
        }

        void frame(DurableState.RuntimeFrame frame) throws IOException {
            utf(frame.node.descriptor().path());
            output.writeInt(frame.node.descriptor().kind().ordinal());
            output.writeInt(frame.phase);
            output.writeInt(frame.index);
            output.writeInt(frame.attempt);
            instant(frame.wake);
            instant(frame.deadline);
            optional(frame.selected);
            output.writeBoolean(frame.observerStarted);
            slot(frame.entry, frame.entryRole);
            slot(frame.current, frame.currentRole);
            if (frame.key == null) {
                output.writeByte(0);
            } else {
                slot(frame.key, DurableState.SlotRole.user(
                        DurablePlanCompiler.keyRole(frame.node.descriptor().path())));
            }
            if (frame.policyState == null) {
                output.writeByte(0);
            } else {
                slot(frame.policyState, DurableState.SlotRole.user(
                        DurablePlanCompiler.policyRole(frame.node.descriptor().path())));
            }
            output.writeInt(frame.branchOutcomes.size());
            for (DurableState.MachineOutcome branch : frame.branchOutcomes) {
                outcome(branch);
            }
        }

        void outcome(DurableState.MachineOutcome value) throws IOException {
            if (value == null) {
                output.writeByte(-1);
                return;
            }
            Outcome<?> outcome = value.outcome();
            output.writeByte(outcome.kind().ordinal());
            if (outcome instanceof Outcome.Accepted) {
                slot(((Outcome.Accepted<?>) outcome).value(), value.acceptedRole());
            } else if (outcome instanceof Outcome.Rejected) {
                reason(((Outcome.Rejected<?>) outcome).reason());
            } else if (outcome instanceof Outcome.Skipped) {
                reason(((Outcome.Skipped<?>) outcome).reason());
            } else if (outcome instanceof Outcome.Failed) {
                failure(((Outcome.Failed<?>) outcome).failure());
            } else {
                throw frameMismatch("Unknown Outcome subtype: " + outcome.getClass().getName());
            }
        }

        void slot(Object value, DurableState.SlotRole role) throws IOException {
            Objects.requireNonNull(value, "slot value must not be null");
            Objects.requireNonNull(role, "slot role must not be null");
            if (role instanceof DurableState.SlotRole.User) {
                DurableState.SlotRole.User user = (DurableState.SlotRole.User) role;
                output.writeByte(1);
                utf(user.hint());
                user(user.hint(), value);
            } else if (role instanceof DurableState.SlotRole.Resumed) {
                if (!(value instanceof Resumed)) {
                    throw frameMismatch("Resumed role has a non-Resumed value");
                }
                DurableState.SlotRole.Resumed resumedRole =
                        (DurableState.SlotRole.Resumed) role;
                Resumed<?, ?> resumed = (Resumed<?, ?>) value;
                output.writeByte(2);
                utf(resumedRole.point());
                slot(resumed.state(), resumedRole.state());
                slot(resumed.signal(), DurableState.SlotRole.user(
                        DurablePlanCompiler.resumeRole(resumedRole.point())));
            } else if (role instanceof DurableState.SlotRole.Recovery) {
                if (!(value instanceof Recovery)) {
                    throw frameMismatch("Recovery role has a non-Recovery value");
                }
                DurableState.SlotRole.Recovery recoveryRole =
                        (DurableState.SlotRole.Recovery) role;
                Recovery<?> recovery = (Recovery<?>) value;
                output.writeByte(3);
                slot(recovery.input(), recoveryRole.input());
                failure(recovery.failure());
            } else {
                throw frameMismatch("Unknown slot role: " + role.getClass().getName());
            }
        }

        private void user(String role, Object value) {
            if (!knownRoles.contains(role)) {
                throw frameMismatch("Runtime contains an unknown state slot role: " + role);
            }
            Object previous = valuesByRole.get(role);
            if (previous != null) {
                if (previous != value) {
                    throw frameMismatch("State slot role contains divergent values: " + role);
                }
                return;
            }
            StoredValue stored = encodeUser(mapper, role, value);
            valuesByRole.put(role, value);
            slots.put(role, stored);
        }

        private void reason(Reason reason) throws IOException {
            utf(reason.code());
            utf(reason.message());
            strings(reason.details());
        }

        private void failure(Failure failure) throws IOException {
            utf(failure.code());
            utf(failure.message());
            strings(failure.details());
        }

        private void strings(Map<String, String> values) throws IOException {
            TreeMap<String, String> sorted = new TreeMap<String, String>(values);
            output.writeInt(sorted.size());
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                utf(entry.getKey());
                utf(entry.getValue());
            }
        }

        private void instant(Instant value) throws IOException {
            output.writeBoolean(value != null);
            if (value != null) {
                output.writeLong(value.getEpochSecond());
                output.writeInt(value.getNano());
            }
        }

        private void optional(String value) throws IOException {
            output.writeBoolean(value != null);
            if (value != null) utf(value);
        }

        private void utf(String value) throws IOException {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_TEXT_BYTES) throw frameMismatch("Metadata text is too large");
            output.writeInt(encoded.length);
            output.write(encoded);
        }
    }

    private static final class DecodedSlot {
        private final Object value;
        private final DurableState.SlotRole role;

        DecodedSlot(Object value, DurableState.SlotRole role) {
            this.value = value;
            this.role = role;
        }
    }

    private static final class Decoder {
        private final DataInputStream input;
        private final Map<String, StoredValue> slots;
        private final StateMapper mapper;
        private final DurablePlanCompiler.Definition definition;
        private final LinkedHashSet<String> usedRoles = new LinkedHashSet<String>();
        private final LinkedHashMap<String, Object> values =
                new LinkedHashMap<String, Object>();

        Decoder(byte[] metadata, Map<String, StoredValue> slots, StateMapper mapper,
                DurablePlanCompiler.Definition definition) {
            this.input = new DataInputStream(new ByteArrayInputStream(metadata));
            this.slots = slots;
            this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
            this.definition = Objects.requireNonNull(definition,
                    "definition must not be null");
        }

        DurableState.RuntimeFrame frame() throws IOException {
            String path = utf();
            int kindOrdinal = input.readInt();
            NodeDescriptor.Kind[] kinds = NodeDescriptor.Kind.values();
            if (kindOrdinal < 0 || kindOrdinal >= kinds.length) {
                throw frameMismatch("Invalid node kind at " + path);
            }
            DurablePlanNode node = definition.byPath().get(path);
            if (node == null) throw frameMismatch("Unknown frame node path: " + path);
            if (node.descriptor().kind() != kinds[kindOrdinal]) {
                throw frameMismatch("Frame node kind does not match plan at " + path);
            }
            int phase = input.readInt();
            int index = input.readInt();
            int attempt = input.readInt();
            Instant wake = instant();
            Instant deadline = instant();
            String selected = optional();
            boolean observerStarted = input.readBoolean();
            DecodedSlot entry = requiredSlot("frame entry");
            DecodedSlot current = requiredSlot("frame current");
            DecodedSlot key = nullableSlot();
            DecodedSlot policy = nullableSlot();
            DurableState.RuntimeFrame restored = new DurableState.RuntimeFrame(
                    node, entry.value, entry.role);
            restored.current = current.value;
            restored.currentRole = current.role;
            restored.key = key == null ? null : key.value;
            restored.policyState = policy == null ? null : policy.value;
            restored.phase = phase;
            restored.index = index;
            restored.attempt = attempt;
            restored.wake = wake;
            restored.deadline = deadline;
            restored.selected = selected;
            restored.observerStarted = observerStarted;
            int branchCount = count("parallel branch outcome count");
            if (branchCount != restored.branchOutcomes.size()) {
                throw frameMismatch("Parallel branch outcome count does not match plan at " + path);
            }
            for (int i = 0; i < branchCount; i++) {
                restored.branchOutcomes.set(i, outcome());
            }
            return restored;
        }

        DurableState.MachineOutcome outcome() throws IOException {
            int tag = input.readByte();
            if (tag == -1) return null;
            Outcome.Kind[] kinds = Outcome.Kind.values();
            if (tag < 0 || tag >= kinds.length) throw frameMismatch("Invalid Outcome kind");
            Outcome.Kind kind = kinds[tag];
            if (kind == Outcome.Kind.ACCEPTED) {
                DecodedSlot accepted = requiredSlot("accepted outcome");
                return DurableState.MachineOutcome.accepted(
                        accepted.value, accepted.role);
            }
            if (kind == Outcome.Kind.REJECTED) {
                return DurableState.MachineOutcome.of(Outcome.rejected(reason()));
            }
            if (kind == Outcome.Kind.SKIPPED) {
                return DurableState.MachineOutcome.of(Outcome.skipped(reason()));
            }
            return DurableState.MachineOutcome.of(Outcome.failed(failure()));
        }

        DecodedSlot nullableSlot() throws IOException {
            int tag = input.readByte();
            if (tag == 0) return null;
            return slot(tag);
        }

        private DecodedSlot requiredSlot(String name) throws IOException {
            DecodedSlot value = nullableSlot();
            if (value == null) throw frameMismatch("Missing " + name);
            return value;
        }

        private DecodedSlot slot(int tag) throws IOException {
            if (tag == 1) {
                String role = utf();
                return new DecodedSlot(user(role), DurableState.SlotRole.user(role));
            }
            if (tag == 2) {
                String point = utf();
                DecodedSlot state = requiredSlot("resumed state");
                DecodedSlot signal = requiredSlot("resume signal");
                if (!(signal.role instanceof DurableState.SlotRole.User)
                        || !DurablePlanCompiler.resumeRole(point).equals(
                        ((DurableState.SlotRole.User) signal.role).hint())) {
                    throw frameMismatch("Resume signal role does not match ResumePoint " + point);
                }
                return new DecodedSlot(new Resumed<Object, Object>(
                        state.value, signal.value),
                        new DurableState.SlotRole.Resumed(state.role, point));
            }
            if (tag == 3) {
                DecodedSlot inputValue = requiredSlot("recovery input");
                Failure failure = failure();
                return new DecodedSlot(new Recovery<Object>(inputValue.value, failure),
                        new DurableState.SlotRole.Recovery(inputValue.role));
            }
            throw frameMismatch("Invalid state slot reference tag: " + tag);
        }

        private Object user(String role) {
            if (!definition.slotRoles().contains(role)) {
                throw frameMismatch("Snapshot contains an invalid state slot role: " + role);
            }
            StoredValue stored = slots.get(role);
            if (stored == null) throw codec("Missing encoded state slot: " + role, null);
            usedRoles.add(role);
            Object value = values.get(role);
            if (value != null) return value;
            try {
                value = Objects.requireNonNull(mapper.decode(stored),
                        "state mapper returned null state");
            } catch (DurableException error) {
                throw error;
            } catch (Exception error) {
                throw codec("Cannot decode state slot " + role, error);
            }
            values.put(role, value);
            return value;
        }

        /** 从信封槽重建 pending resume 信号（标记角色已被引用）。 */
        Object resumeSignal(String role) {
            return user(role);
        }

        private Reason reason() throws IOException {
            return new Reason(utf(), utf(), strings());
        }

        private Failure failure() throws IOException {
            return new Failure(utf(), utf(), strings());
        }

        private Map<String, String> strings() throws IOException {
            int size = count("diagnostic detail count");
            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            for (int i = 0; i < size; i++) {
                String key = utf();
                if (values.put(key, utf()) != null) {
                    throw frameMismatch("Duplicate diagnostic detail: " + key);
                }
            }
            return values;
        }

        private Instant instant() throws IOException {
            if (!input.readBoolean()) return null;
            return Instant.ofEpochSecond(input.readLong(), input.readInt());
        }

        private String optional() throws IOException {
            return input.readBoolean() ? utf() : null;
        }

        private String utf() throws IOException {
            int length = input.readInt();
            if (length < 0 || length > MAX_TEXT_BYTES) {
                throw frameMismatch("Invalid metadata text length");
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private int count(String name) throws IOException {
            int value = input.readInt();
            if (value < 0 || value > MAX_COUNT) throw frameMismatch("Invalid " + name);
            return value;
        }
    }

    static DurableException frameMismatch(String message) {
        return new DurableException(DurableException.Error.FRAME_MISMATCH, message);
    }

    static DurableException frameMismatch(String message, Throwable cause) {
        return new DurableException(DurableException.Error.FRAME_MISMATCH, message, cause);
    }

    static DurableException codec(String message, Throwable cause) {
        return cause == null
                ? new DurableException(DurableException.Error.CODEC_FAILURE, message)
                : new DurableException(DurableException.Error.CODEC_FAILURE, message, cause);
    }
}
