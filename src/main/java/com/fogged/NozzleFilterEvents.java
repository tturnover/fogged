package com.fogged;

import com.fogged.block.NozzleFilterBlock;
import com.fogged.registry.ModBlocks;
import com.fogged.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Turns a {@code create:nozzle} into a {@code fogged:nozzle_filter}: right-click the nozzle while
 * holding at least {@link #REQUIRED} fluff balls and the nozzle block is replaced in place (inheriting
 * its facing) and the fluff balls consumed. Replacing the block outright -- rather than layering on top
 * -- is deliberate: it drops Create's own nozzle behaviour so the two air sources never conflict.
 *
 * <p>Soft integration only: the nozzle is matched by registry id, so this compiles and runs whether or
 * not Create is installed (with Create absent, no nozzle ever exists and the handler never fires).
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class NozzleFilterEvents {

    /** Fluff balls consumed per conversion. */
    public static final int REQUIRED = 3;

    private static final ResourceLocation CREATE_NOZZLE =
            ResourceLocation.fromNamespaceAndPath("create", "nozzle");

    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!Config.ITEMS_ENABLED.get()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!stack.is(ModItems.FLUFF_BALL.get()) || stack.getCount() < REQUIRED) {
            return;
        }
        Level level = event.getLevel();
        BlockState nozzle = level.getBlockState(event.getPos());
        if (!CREATE_NOZZLE.equals(BuiltInRegistries.BLOCK.getKey(nozzle.getBlock()))) {
            return;
        }

        // The swap is authoritative, so only the server performs it. The client must NOT cancel here --
        // cancelling client-side would stop the use packet from ever reaching the server. It just lets
        // the (no-op) vanilla nozzle interaction proceed; the server-side pass below does the real work.
        if (level.isClientSide) {
            return;
        }

        // Server: swallow the interaction so Create/vanilla don't also act, then replace the nozzle.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        Player player = event.getEntity();
        BlockState filter = ModBlocks.NOZZLE_FILTER.get().defaultBlockState()
                .setValue(NozzleFilterBlock.FACING, nozzleFacing(nozzle));
        level.setBlockAndUpdate(event.getPos(), filter);

        SoundType sound = filter.getSoundType();
        level.playSound(null, event.getPos(), sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.9F);
        player.swing(event.getHand(), true); // arm swing (the client didn't predict success)
        if (!player.getAbilities().instabuild) {
            stack.shrink(REQUIRED);
        }
    }

    /**
     * Breaking a filter returns the {@code create:nozzle} it was made from. The {@link #REQUIRED} fluff
     * balls come back via the block's loot table ({@code ModBlockLoot}); the nozzle is dropped here
     * instead because Create is a soft dependency, so its item can only be resolved by id at runtime.
     * Creative breaks drop nothing (matching how loot tables skip creative), and non-player breaks
     * (explosions) still return the nozzle.
     */
    @SubscribeEvent
    static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getState().getBlock() != ModBlocks.NOZZLE_FILTER.get()) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null && player.getAbilities().instabuild) {
            return; // creative: no drops
        }
        if (!(event.getLevel() instanceof Level level) || level.isClientSide) {
            return;
        }
        dropNozzle(level, event.getPos());
    }

    /** Drop a single {@code create:nozzle} item at {@code pos} (no-op when Create is absent). */
    public static void dropNozzle(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return;
        }
        BuiltInRegistries.ITEM.getOptional(CREATE_NOZZLE)
                .ifPresent(item -> Block.popResource(level, pos, new ItemStack(item)));
    }

    /**
     * Revert a nozzle filter back into a plain {@code create:nozzle}, keeping {@code facing}, and drop
     * the {@link #REQUIRED} fluff balls that made it. Used when the attached fan spins too fast and the
     * filter overloads (see {@link com.fogged.block.NozzleFilterBlockEntity}). Server-side only.
     */
    public static void revertToNozzle(Level level, BlockPos pos, Direction facing) {
        if (level.isClientSide) {
            return;
        }
        BreatheSpheres.remove(level, pos);
        Block nozzle = BuiltInRegistries.BLOCK.getOptional(CREATE_NOZZLE).orElse(null);
        if (nozzle == null) {
            // Create not present (shouldn't happen -- the filter only exists with Create): just clear it.
            level.removeBlock(pos, false);
        } else {
            level.setBlockAndUpdate(pos, withFacing(nozzle.defaultBlockState(), facing));
        }
        Block.popResource(level, pos, new ItemStack(ModItems.FLUFF_BALL.get(), REQUIRED));
    }

    /** Read the nozzle's {@code facing} generically (no compile dependency on Create's block class). */
    private static Direction nozzleFacing(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("facing") && state.getValue(property) instanceof Direction dir) {
                return dir;
            }
        }
        return Direction.UP;
    }

    /** Set a {@code facing} Direction property on {@code state} generically, if it has one. */
    private static BlockState withFacing(BlockState state, Direction facing) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("facing") && property.getValueClass() == Direction.class) {
                return setValue(state, property, facing);
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setValue(BlockState state, Property<?> property,
            Comparable<?> value) {
        return state.setValue((Property<T>) property, (T) value);
    }

    private NozzleFilterEvents() {}
}
