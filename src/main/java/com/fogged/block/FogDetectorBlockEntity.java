package com.fogged.block;

import com.fogged.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Base-only logic: drives redstone re-evaluation as the fog plane drifts, and stores the column's pole
 * wood (the stripped-log id, default {@code stripped_oak_log}). Extensions read it back via
 * {@code FogDetectorBlock.baseEntity} for their model data.
 */
public class FogDetectorBlockEntity extends BlockEntity {
    private static final long INTERVAL = 8L;
    private static final String LOG_ID_KEY = "LogId";

    private int lastPower = -1;
    private ResourceLocation logId = FogDetectorBlock.DEFAULT_LOG;

    public FogDetectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FOG_DETECTOR.get(), pos, state);
    }

    /** The stripped-log item/block id the column's pole wears. */
    public ResourceLocation getLogId() {
        return logId;
    }

    /** Set the pole wood and sync to clients; they refresh the whole column (see {@link #refreshColumn}). */
    public void setLogId(ResourceLocation id) {
        this.logId = id;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState st = getBlockState();
            level.sendBlockUpdated(worldPosition, st, st, Block.UPDATE_CLIENTS);
        }
    }

    // Refresh model data AND force a re-render for every column segment: requestModelDataUpdate alone
    // updates the data but does not re-mesh, so the pole would otherwise change only on the next block update.
    private void refreshColumn() {
        if (level == null || !level.isClientSide) {
            return;
        }
        Direction up = getBlockState().hasProperty(FogDetectorBlock.VERTICAL_DIRECTION)
                ? getBlockState().getValue(FogDetectorBlock.VERTICAL_DIRECTION) : Direction.UP;
        int minY = worldPosition.getY();
        int maxY = worldPosition.getY();
        BlockPos p = worldPosition;
        while (level.getBlockState(p).getBlock() instanceof FogDetectorBlock) {
            if (level.getBlockEntity(p) instanceof FogDetectorBlockEntity be) {
                be.requestModelDataUpdate();
            }
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            p = p.relative(up);
        }
        net.minecraft.client.Minecraft.getInstance().levelRenderer.setBlocksDirty(
                worldPosition.getX(), minY, worldPosition.getZ(),
                worldPosition.getX(), maxY, worldPosition.getZ());
    }

    // Carry the base's log id as model data so the dynamic model can retexture this segment's pole.
    @Override
    public ModelData getModelData() {
        ResourceLocation id = logId;
        if (level != null) {
            FogDetectorBlockEntity base = FogDetectorBlock.baseEntity(level, worldPosition, getBlockState());
            if (base != null) {
                id = base.getLogId();
            }
        }
        return ModelData.builder().with(FogDetectorBlock.LOG_ID, id).build();
    }

    // Client sync (chunk load or live reskin) -> refresh the column so the pole shows the new wood at once.
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        refreshColumn();
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries) {
        super.onDataPacket(net, packet, registries);
        refreshColumn();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FogDetectorBlockEntity be) {
        if ((level.getGameTime() % INTERVAL) != 0L
                || !(state.getBlock() instanceof FogDetectorBlock)
                || !FogDetectorBlock.isBase(level, pos, state)) {
            return;
        }

        // Light the base plus the "waterline" (topmost submerged segment) -- a single lit ring marking the
        // fog level; when fully submerged the waterline is the tip.
        double surfaceY = com.fogged.Config.breathHeight(level) + com.fogged.Config.PLANE_SURFACE_OFFSET;
        Direction up = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION);
        BlockPos waterline = null;
        BlockPos scan = pos;
        while (level.getBlockState(scan).getBlock() instanceof FogDetectorBlock) {
            if (com.fogged.PlaneSensor.worldY(level, scan) < surfaceY) {
                waterline = scan; // keeps rising to the topmost submerged segment
            }
            scan = scan.relative(up);
        }
        BlockPos p = pos;
        while (level.getBlockState(p).getBlock() instanceof FogDetectorBlock) {
            BlockState st = level.getBlockState(p);
            // The base lights whenever any part is submerged; the waterline segment lights too.
            boolean lit = p.equals(waterline) || (p.equals(pos) && waterline != null);
            if (st.hasProperty(FogDetectorBlock.LIT) && st.getValue(FogDetectorBlock.LIT) != lit) {
                level.setBlock(p, st.setValue(FogDetectorBlock.LIT, lit), Block.UPDATE_ALL); // re-render + relight
            }
            p = p.relative(up);
        }

        // Redstone: strength scales with the submerged fraction; nudge neighbours when it changes.
        int power = FogDetectorBlock.columnPower(level, pos, state);
        if (power != be.lastPower) {
            be.lastPower = power;
            level.updateNeighborsAt(pos, state.getBlock());
            // Propagate through the block it rests on, so strong power conducts.
            Direction support = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION).getOpposite();
            level.updateNeighborsAt(pos.relative(support), state.getBlock());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(LOG_ID_KEY, logId.toString());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(LOG_ID_KEY)) {
            ResourceLocation parsed = ResourceLocation.tryParse(tag.getString(LOG_ID_KEY));
            logId = parsed != null ? parsed : FogDetectorBlock.DEFAULT_LOG;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString(LOG_ID_KEY, logId.toString());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
