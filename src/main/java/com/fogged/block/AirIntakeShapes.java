package com.fogged.block;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The air intake's shape: four stepped boxes following its model, as Create steps its shapes. GENERATED
 * from the intake models (voxelized at a pixel, then each quarter of the height boxed) -- regenerate rather
 * than edit. The block it feeds (a compressor, a duct) seals the way through, not this.
 */
final class AirIntakeShapes {

    // The hood, authored feeding south: 4 boxes.
    private static final int[][] HOOD = {
            {0, 0, 11, 16, 4, 16},
            {0, 4, 9, 16, 8, 16},
            {0, 8, 7, 16, 12, 16},
            {0, 12, 6, 16, 16, 16}
    };

    // The frame, authored feeding down: 3 boxes.
    private static final int[][] FRAME = {
            {1, 0, 1, 15, 4, 15},
            {0, 4, 0, 16, 8, 16},
            {0, 8, 0, 16, 10, 16}
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
