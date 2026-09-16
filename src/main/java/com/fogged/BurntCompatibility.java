package com.fogged;

import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Burnt (better vanilla fire): its flames and everything it has smoldering or blazing go out under
 * the murk, through the mod's own extinguish -- the one its rain and fire extinguisher use -- so a
 * burning log ends as the charred one it would have anyway, not as something this mod guessed at.
 * Burnt Additions' soul fire the same, through its own.
 *
 * <p>Reached by name, so Burnt stays optional (as in {@link CreateCompatibility}). Without it, or if
 * a procedure cannot be found, the loose flames are simply removed and the rest is left alone.
 */
public final class BurntCompatibility {
    private BurntCompatibility() {}

    private static final boolean BURNT = ModList.get().isLoaded("burnt");
    private static final boolean ADDITIONS = ModList.get().isLoaded("burnt_additions");

    // Burnt's own tags: the flames themselves, and the solid blocks that are burning.
    private static final TagKey<Block> FIRE = tag("burnt", "fire");
    private static final TagKey<Block> ON_FIRE = tag("burnt", "on_fire");
    // Burnt Additions' soul flames; what it has soul-burning carries no tag and is known by name.
    private static final TagKey<Block> SOUL_FIRE = tag("burnt_additions", "soul_fire");

    private static final Procedure EXTINGUISH = new Procedure(
            "net.pixelbank.burnt.procedures.ExtinguishBlockProcedure", true);
    private static final Procedure SOUL_EXTINGUISH = new Procedure(
            "net.pixelbank.burntadditions.procedures.SoulExtinguishProcedure", false);

    private static TagKey<Block> tag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** Whether this block is Burnt's fire, or something Burnt has burning. */
    public static boolean isBurning(BlockState state) {
        return (BURNT && (state.is(FIRE) || state.is(ON_FIRE))) || isSoulBurning(state);
    }

    private static boolean isSoulBurning(BlockState state) {
        if (!ADDITIONS) {
            return false;
        }
        if (state.is(SOUL_FIRE)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals("burnt_additions") && id.getPath().startsWith("smoldering_soul_");
    }

    /** Put it out as Burnt would. */
    public static void extinguish(Level level, BlockPos pos, BlockState state) {
        boolean done = isSoulBurning(state) ? SOUL_EXTINGUISH.run(level, pos) : EXTINGUISH.run(level, pos);
        if (!done && (state.is(FIRE) || state.is(SOUL_FIRE))) {
            level.removeBlock(pos, false);
        }
    }

    // One of Burnt's static extinguish procedures, execute(LevelAccessor, x, y, z[, splash]), resolved
    // on first use and dropped on the first failure.
    private static final class Procedure {
        private final String className;
        // Burnt's extinguish takes a flag for its splash -- the white burst its rain and extinguisher
        // throw -- which is water's, not the murk's, so it is passed off where the procedure has it.
        private final boolean splashFlag;
        private volatile Method method;
        private volatile boolean resolved;

        Procedure(String className, boolean splashFlag) {
            this.className = className;
            this.splashFlag = splashFlag;
        }

        boolean run(Level level, BlockPos pos) {
            Method m = resolve();
            if (m == null) {
                return false;
            }
            try {
                if (splashFlag) {
                    m.invoke(null, level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, false);
                } else {
                    m.invoke(null, level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Fogged.LOGGER.warn("Fogged: {} failed at {}; removing flames only from here on", className, pos, e);
                method = null;
                return false;
            }
        }

        private Method resolve() {
            if (resolved) {
                return method;
            }
            resolved = true;
            try {
                Class<?> c = Class.forName(className);
                method = splashFlag
                        ? c.getMethod("execute", LevelAccessor.class, double.class, double.class, double.class, boolean.class)
                        : c.getMethod("execute", LevelAccessor.class, double.class, double.class, double.class);
            } catch (ReflectiveOperationException e) {
                Fogged.LOGGER.warn("Fogged: Burnt is present but {} was not found; only its loose flames "
                        + "will go out under the murk", className, e);
            }
            return method;
        }
    }
}
