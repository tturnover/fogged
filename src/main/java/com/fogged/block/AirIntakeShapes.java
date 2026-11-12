package com.fogged.block;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The air intake's shape: stepped boxes following its model, as a hopper's follows its funnel. GENERATED
 * from the intake models (voxelized at a pixel, the hood's lining cut out, a pixel plate across the side
 * it feeds so the cell stays sealed to air) -- regenerate rather than edit.
 */
final class AirIntakeShapes {

    // The hood, authored feeding south: 32 boxes.
    private static final int[][] HOOD = {
            {0, 13, 6, 16, 15, 8},
            {0, 11, 7, 2, 13, 13},
            {14, 11, 7, 16, 13, 13},
            {0, 8, 8, 2, 11, 14},
            {14, 8, 8, 16, 11, 14},
            {0, 13, 8, 2, 16, 12},
            {14, 13, 8, 16, 16, 12},
            {2, 14, 8, 14, 16, 10},
            {0, 6, 9, 2, 8, 16},
            {14, 6, 9, 16, 8, 16},
            {0, 4, 10, 2, 6, 16},
            {14, 4, 10, 16, 6, 16},
            {2, 15, 10, 14, 16, 12},
            {0, 1, 11, 2, 4, 16},
            {14, 1, 11, 16, 4, 16},
            {0, 0, 12, 16, 1, 16},
            {2, 1, 12, 14, 2, 16},
            {2, 3, 12, 14, 8, 16},
            {0, 13, 12, 2, 14, 13},
            {14, 13, 12, 16, 14, 13},
            {1, 14, 12, 15, 15, 16},
            {0, 11, 13, 2, 12, 14},
            {14, 11, 13, 16, 12, 14},
            {1, 12, 13, 2, 14, 16},
            {14, 12, 13, 15, 14, 16},
            {2, 2, 14, 14, 3, 16},
            {0, 8, 14, 16, 10, 16},
            {1, 10, 14, 15, 12, 16},
            {2, 12, 14, 14, 14, 16},
            {0, 10, 15, 1, 16, 16},
            {15, 10, 15, 16, 16, 16},
            {1, 15, 15, 15, 16, 16}
    };

    // The frame, authored feeding down: 3 boxes.
    private static final int[][] FRAME = {
            {0, 0, 0, 16, 1, 16},
            {0, 4, 0, 16, 10, 16},
            {1, 1, 1, 15, 4, 15}
    };

    /** Per the way the intake feeds. */
    static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction feeds : Direction.values()) {
            VoxelShape shape = Shapes.empty();
            for (int[] b : feeds.getAxis() == Direction.Axis.Y ? FRAME : HOOD) {
                int[] t = turn(b, feeds);
                shape = Shapes.or(shape, Block.box(t[0], t[1], t[2], t[3], t[4], t[5]));
            }
            SHAPES.put(feeds, shape.optimize());
        }
    }

    // A box turned as the blockstate turns the model: about y for the hood (south 0, west 90, north 180,
    // east 270, clockwise from above), about x by 180 for a frame feeding up.
    private static int[] turn(int[] b, Direction feeds) {
        int x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
        return switch (feeds) {
            case DOWN, SOUTH -> b;
            case UP -> new int[] {x0, 16 - y1, 16 - z1, x1, 16 - y0, 16 - z0};
            case WEST -> new int[] {16 - z1, y0, x0, 16 - z0, y1, x1};
            case NORTH -> new int[] {16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0};
            case EAST -> new int[] {z0, y0, 16 - x1, z1, y1, 16 - x0};
        };
    }

    private AirIntakeShapes() {}
}
