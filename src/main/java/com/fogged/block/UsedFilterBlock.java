package com.fogged.block;

import java.util.List;
import java.util.function.Consumer;

import com.fogged.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A polluted filter set down as a block (see {@link UsedFilterBlockEntity}). It stands in for the block
 * it is -- looks, sounds, mines, burns and breaks like it, and is drawn as it, shaded toward the murk's
 * colour as it wears (UsedFilterRenderer) -- so the pollution can live in its own NBT. Clean, it turns
 * back into that block. Each wool has its own, a full copy of the wool's properties and in all its tags
 * (ModBlocks#USED_WOOL, UsedFilterTags); a filter that is not wool gets the generic one.
 */
public class UsedFilterBlock extends BaseEntityBlock {
    public static final MapCodec<UsedFilterBlock> CODEC = simpleCodec(UsedFilterBlock::new);

    /**
     * How polluted it is, in the item's steps (0 clean .. MAX_POLLUTION clogged). In the state, so it comes
     * with the block itself: shaded right as it appears, and seen by the particles' random tick.
     */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty POLLUTION =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("pollution", 0,
                    com.fogged.registry.ModDataComponents.MAX_POLLUTION);

    // The block this one stands in for, by id; null for the generic one, which takes it from its block
    // entity alone.
    private final net.minecraft.resources.ResourceLocation originalId;

    public UsedFilterBlock(Properties props) {
        this(props, null);
    }

    /** The used filter of the block {@code originalId} (com.fogged.UsedFilters). */
    public UsedFilterBlock(Properties props, net.minecraft.resources.ResourceLocation originalId) {
        super(props);
        this.originalId = originalId;
        registerDefaultState(stateDefinition.any().setValue(POLLUTION, 0));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(POLLUTION);
    }

    /** Whether this is one block's own used filter, baked as that block's model (UsedFilterModels). */
    public boolean standsInForOne() {
        return originalId != null;
    }

    /** How worn {@code state} is, 0..1, by its steps. */
    public static float wear(BlockState state) {
        return state.hasProperty(POLLUTION)
                ? state.getValue(POLLUTION) / (float) com.fogged.registry.ModDataComponents.MAX_POLLUTION : 0.0F;
    }

    // The murk it holds seeping out: the scour's steam off its open faces, the more the dirtier it is.
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
        if (random.nextFloat() >= wear(state)) {
            return;
        }
        Direction side = Direction.getRandom(random);
        BlockPos out = pos.relative(side);
        if (level.getBlockState(out).isSolidRender(level, out)) {
            return; // walled up on that side: nothing seeps out there
        }
        double x = pos.getX() + 0.5 + side.getStepX() * 0.52 + (side.getStepX() == 0 ? random.nextDouble() - 0.5 : 0.0) * 0.9;
        double y = pos.getY() + 0.5 + side.getStepY() * 0.52 + (side.getStepY() == 0 ? random.nextDouble() - 0.5 : 0.0) * 0.9;
        double z = pos.getZ() + 0.5 + side.getStepZ() * 0.52 + (side.getStepZ() == 0 ? random.nextDouble() - 0.5 : 0.0) * 0.9;
        level.addParticle(com.fogged.FogScour.steam(), x, y, z,
                side.getStepX() * 0.01, 0.015 + Math.max(0, side.getStepY()) * 0.01, side.getStepZ() * 0.01);
    }

    /** The block this one stands in for, or null: the generic one, or a block not made (yet). */
    public net.minecraft.world.level.block.Block originalBlock() {
        if (originalId == null) {
            return null;
        }
        net.minecraft.world.level.block.Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(originalId);
        return b == Blocks.AIR ? null : b;
    }

    // Named and itemed as the block it stands in for, everywhere a block is named or turned into an item.
    @Override
    public String getDescriptionId() {
        net.minecraft.world.level.block.Block b = originalBlock();
        return b != null ? b.getDescriptionId() : super.getDescriptionId();
    }

    @Override
    public net.minecraft.world.item.Item asItem() {
        net.minecraft.world.level.block.Block b = originalBlock();
        return b != null ? b.asItem() : super.asItem();
    }

    @Override
    public float getFriction() {
        net.minecraft.world.level.block.Block b = originalBlock();
        return b != null ? b.getFriction() : super.getFriction();
    }

    @Override
    public float getSpeedFactor() {
        net.minecraft.world.level.block.Block b = originalBlock();
        return b != null ? b.getSpeedFactor() : super.getSpeedFactor();
    }

    @Override
    public float getJumpFactor() {
        net.minecraft.world.level.block.Block b = originalBlock();
        return b != null ? b.getJumpFactor() : super.getJumpFactor();
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /** The block the filter at {@code pos} is; white wool while that is not known yet. */
    public static BlockState original(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof UsedFilterBlockEntity be) {
            return be.original();
        }
        net.minecraft.world.level.block.Block b = level.getBlockState(pos).getBlock() instanceof UsedFilterBlock used
                ? used.originalBlock() : null;
        return b != null ? b.defaultBlockState() : Blocks.WHITE_WOOL.defaultBlockState();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // One block's own is baked into the chunk as that block's model, shaded by its colour handler;
        // the generic one only learns what it is from its block entity, so that draws it.
        return standsInForOne() ? RenderShape.MODEL : RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new UsedFilterBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.USED_FILTER.get(), UsedFilterBlockEntity::serverTick);
    }

    // --- As the block it is ---

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return original(level, pos).getDestroyProgress(player, level, pos);
    }

    @Override
    public boolean canHarvestBlock(BlockState state, BlockGetter level, BlockPos pos, Player player) {
        return original(level, pos).canHarvestBlock(level, pos, player);
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, Entity entity) {
        return original(level, pos).getSoundType(level, pos, entity);
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return original(level, pos).getExplosionResistance(level, pos, explosion);
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return original(level, pos).getFlammability(level, pos, direction);
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return original(level, pos).getFireSpreadSpeed(level, pos, direction);
    }

    @Override
    public MapColor getMapColor(BlockState state, BlockGetter level, BlockPos pos, MapColor defaultColor) {
        return original(level, pos).getMapColor(level, pos);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return level.getBlockEntity(pos) instanceof UsedFilterBlockEntity be ? be.asItem() : ItemStack.EMPTY;
    }

    // What the block it is drops, with the pollution it still holds on the filter among it.
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (!(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof UsedFilterBlockEntity be)) {
            return List.of();
        }
        List<ItemStack> drops = be.original().getDrops(params);
        ItemStack self = be.asItem();
        for (int i = 0; i < drops.size(); i++) {
            ItemStack drop = drops.get(i);
            if (!self.isEmpty() && drop.is(self.getItem())) {
                drops.set(i, self.copyWithCount(drop.getCount()));
            }
        }
        return drops;
    }

    // --- Particles ---
    // One block's own is baked as that block's model and shaded by its colour handler, so the game's own
    // particles already come out in its texture and shade: it is left to them. The generic one's model is
    // only a stand-in, so it spawns them itself, as the game would, in the texture of the block it is.

    @Override
    public boolean addLandingEffects(BlockState state1, ServerLevel level, BlockPos pos, BlockState state2,
            LivingEntity entity, int numberOfParticles) {
        if (standsInForOne()) {
            return false;
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, original(level, pos)),
                entity.getX(), entity.getY(), entity.getZ(), numberOfParticles, 0.0, 0.0, 0.0, 0.15);
        return true;
    }

    @Override
    public boolean addRunningEffects(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (standsInForOne()) {
            return false;
        }
        Vec3 motion = entity.getDeltaMovement();
        double w = entity.getBbWidth();
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, original(level, pos)),
                entity.getX() + (level.random.nextDouble() - 0.5) * w, entity.getY() + 0.1,
                entity.getZ() + (level.random.nextDouble() - 0.5) * w, motion.x * -4.0, 1.5, motion.z * -4.0);
        return true;
    }

    @Override
    public void initializeClient(Consumer<net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions> consumer) {
        consumer.accept(new net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions() {
            @Override
            public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos,
                    net.minecraft.client.particle.ParticleEngine manager) {
                if (standsInForOne()) {
                    return false;
                }
                manager.destroy(pos, original(level, pos));
                return true;
            }

            // As ParticleEngine#crack: one speck on the face being mined, anywhere across it.
            @Override
            public boolean addHitEffects(BlockState state, Level level, HitResult target,
                    net.minecraft.client.particle.ParticleEngine manager) {
                if (standsInForOne()) {
                    return false;
                }
                if (!(target instanceof net.minecraft.world.phys.BlockHitResult hit)) {
                    return false;
                }
                BlockPos pos = hit.getBlockPos();
                net.minecraft.util.RandomSource random = level.random;
                double x = pos.getX() + random.nextDouble() * 0.8 + 0.1;
                double y = pos.getY() + random.nextDouble() * 0.8 + 0.1;
                double z = pos.getZ() + random.nextDouble() * 0.8 + 0.1;
                switch (hit.getDirection()) {
                    case DOWN -> y = pos.getY() - 0.1;
                    case UP -> y = pos.getY() + 1.1;
                    case NORTH -> z = pos.getZ() - 0.1;
                    case SOUTH -> z = pos.getZ() + 1.1;
                    case WEST -> x = pos.getX() - 0.1;
                    case EAST -> x = pos.getX() + 1.1;
                }
                BlockState original = original(level, pos);
                manager.add(new net.minecraft.client.particle.TerrainParticle(
                        (net.minecraft.client.multiplayer.ClientLevel) level, x, y, z, 0.0, 0.0, 0.0, original, pos)
                        .updateSprite(original, pos).setPower(0.2F).scale(0.6F));
                return true;
            }
        });
    }
}
