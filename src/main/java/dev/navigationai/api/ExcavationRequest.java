package dev.navigationai.api;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A bounded, one-block-wide tunnel, or a staircase rising/descending one block per segment.
 * Origin is the entrance's feet position, which must already be safe to stand in.
 * completedSegments allows an activity to resume an already excavated prefix.
 */
public record ExcavationRequest(BlockPos origin, Direction direction, ExcavationMode mode,
                                int length, int completedSegments) {
    public static final int MAX_LENGTH = 128;

    public ExcavationRequest(BlockPos origin, Direction direction, ExcavationMode mode, int length) {
        this(origin, direction, mode, length, 0);
    }

    public ExcavationRequest {
        origin = Objects.requireNonNull(origin, "origin").immutable();
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(mode, "mode");
        if (direction.getAxis().isVertical()) throw new IllegalArgumentException("direction must be horizontal");
        if (length < 1 || length > MAX_LENGTH) throw new IllegalArgumentException("length must be between 1 and " + MAX_LENGTH);
        if (completedSegments < 0 || completedSegments > length) {
            throw new IllegalArgumentException("completedSegments must be between zero and length");
        }
        long endX = origin.getX() + (long)direction.getStepX() * length;
        long endZ = origin.getZ() + (long)direction.getStepZ() * length;
        long endY = origin.getY() + (mode == ExcavationMode.STAIRCASE_DOWN ? -(long)length : mode == ExcavationMode.STAIRCASE_UP ? (long)length : 0L);
        if (Math.abs((long)origin.getX()) >= 30_000_000L || Math.abs(endX) >= 30_000_000L
                || Math.abs((long)origin.getZ()) >= 30_000_000L || Math.abs(endZ) >= 30_000_000L
                || endY < Integer.MIN_VALUE || Math.max(endY,(long)origin.getY()) + 3 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("excavation coordinates exceed world bounds");
        }
    }

    public BlockPos positionAt(int segment) {
        if (segment < 0 || segment > length) throw new IllegalArgumentException("segment outside excavation");
        BlockPos position = origin.relative(direction, segment);
        return mode == ExcavationMode.STAIRCASE_DOWN ? position.below(segment) : mode == ExcavationMode.STAIRCASE_UP ? position.above(segment) : position;
    }

    /** Dig headroom before the feet block; stairs need extra clearance while crossing the step. */
    public List<BlockPos> clearanceAt(int segment) {
        BlockPos feet = positionAt(segment);
        if(mode==ExcavationMode.STAIRCASE_UP)return List.of(positionAt(Math.max(0,segment-1)).above(2),feet.above(),feet.above(2),feet);
        int height = mode == ExcavationMode.STAIRCASE_DOWN ? 3 : 2;
        return IntStream.range(0, height).mapToObj(index -> feet.above(height - 1 - index)).toList();
    }

    public ExcavationRequest withCompletedSegments(int completed) {
        return new ExcavationRequest(origin, direction, mode, length, completed);
    }
}
