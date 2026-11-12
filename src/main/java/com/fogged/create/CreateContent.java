package com.fogged.create;

import com.fogged.Fogged;
import com.fogged.block.AirCompressorIntakeBlock;
import com.fogged.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * What Fogged adds with Create installed: the kinetic air compressor, made with a wrench on an air
 * compressor's side (see {@link KineticAirCompressorBlock#fitShaft}). Kept in registers of its own,
 * registered from {@link Fogged} only when Create is loaded, so without Create no class here is ever
 * loaded and nothing names Create's. The block has no item -- it only ever comes from a compressor --
 * and its blockstate, models and loot are hand-written under resources: data generation runs without
 * Create.
 */
public final class CreateContent {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Fogged.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Fogged.MODID);

    public static final DeferredBlock<KineticAirCompressorBlock> KINETIC_AIR_COMPRESSOR =
            BLOCKS.registerBlock("kinetic_air_compressor", KineticAirCompressorBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion()); // the shaft shows through its sockets

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KineticAirCompressorBlockEntity>> KINETIC_AIR_COMPRESSOR_BE =
            BLOCK_ENTITIES.register("kinetic_air_compressor", () -> BlockEntityType.Builder
                    .of((pos, state) -> new KineticAirCompressorBlockEntity(CreateContent.KINETIC_AIR_COMPRESSOR_BE.get(), pos, state),
                            KINETIC_AIR_COMPRESSOR.get())
                    .build(null));

    // Into the player's inventory what breaking it would drop, as Create's wrench picks up its own.
    private static void pickUp(ServerLevel level, BlockPos pos, BlockState state, Player player, ItemStack tool) {
        // An intake's filter comes along into the inventory, rather than dropping as it is taken away.
        if (level.getBlockEntity(pos) instanceof com.fogged.block.AirCompressorIntakeBlockEntity intake) {
            ItemStack filter = intake.takeFilter();
            if (!filter.isEmpty()) {
                player.getInventory().placeItemBackInInventory(filter);
            }
        }
        if (!player.isCreative()) {
            for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, tool)) {
                player.getInventory().placeItemBackInInventory(drop);
            }
        }
        level.removeBlock(pos, false);
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                1.0F + level.random.nextFloat() * 0.4F);
    }

    /** A goggled FE compressor block entity; see AirCompressorBlockEntity#create. */
    public static com.fogged.block.AirCompressorBlockEntity goggledCompressor(BlockPos pos, BlockState state) {
        return new GoggledAirCompressorBlockEntity(pos, state);
    }

    /** A goggled intake block entity; see AirCompressorIntakeBlockEntity#create. */
    public static com.fogged.block.AirCompressorIntakeBlockEntity goggledIntake(BlockPos pos, BlockState state) {
        return new GoggledIntakeBlockEntity(pos, state);
    }

    private CreateContent() {}

    /** Called from {@link Fogged} when Create is loaded. */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(CreateContent::onRightClickBlock);
    }

    // A wrench on an air compressor's side fits a shaft socket there (KineticAirCompressorBlock.fitShaft);
    // sneaking, it picks the compressor, an intake or an air duct up, as Create's wrench does its own blocks. A
    // kinetic compressor is Create's own wrenchable and needs none of this (onWrenched, and Create's
    // own pick-up).
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(KineticAirCompressorBlock.wrenchItem())) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        if (event.getEntity().isShiftKeyDown()) {
            if (state.getBlock() instanceof com.fogged.block.CompressorBlock || state.is(ModBlocks.AIR_DUCT.get())
                    || state.getBlock() instanceof AirCompressorIntakeBlock) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                if (event.getLevel() instanceof ServerLevel level) {
                    pickUp(level, pos, state, event.getEntity(), stack);
                }
            }
            return;
        }
        if (!state.is(ModBlocks.AIR_COMPRESSOR.get())) {
            return;
        }
        event.setCanceled(true);
        if (event.getLevel().isClientSide) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (KineticAirCompressorBlock.fitShaft(event.getLevel(), pos, state, event.getFace())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
        } else {
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }
}
