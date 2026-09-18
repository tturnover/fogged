package com.fogged;

import com.momosoftworks.coldsweat.api.event.core.init.DefaultTempModifiersEvent;
import com.momosoftworks.coldsweat.api.event.core.registry.TempModifierRegisterEvent;
import com.momosoftworks.coldsweat.api.util.Temperature;

import com.momosoftworks.coldsweat.common.blockentity.HearthBlockEntity;
import com.momosoftworks.coldsweat.common.blockentity.IceboxBlockEntity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;

/**
 * Everything this mod does with Cold Sweat. Names Cold Sweat classes freely: nothing here runs, and
 * the class is never loaded, unless {@link Fogged} found the mod present -- the SableCompatibility
 * arrangement, with the loaded check kept at the call sites.
 *
 * <p>Two things. The murk is cold: {@link MurkTempModifier} is registered as a temperature modifier
 * and handed to every player, and while their eyes are under the surface it takes a share of the
 * warmth out of the world temperature. And when the murk smothers a hearth (ColdSweatHearthMixin
 * holds its tick), {@link #ejectFuel} throws out what was in its fuel slot, as the sweep does a
 * furnace's -- the fuel already in the tank stays, that being the point of smothering rather than
 * dousing, but nothing new is fed to it while it is under.
 */
public final class ColdSweatCompatibility {

    private static final ResourceLocation MURK = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "murk");

    private ColdSweatCompatibility() {
    }

    /** Register the murk's temperature modifier and hand it to players. Cold Sweat posts both on the game bus. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener((TempModifierRegisterEvent event) -> event.register(MURK, MurkTempModifier::new));
        gameBus.addListener((DefaultTempModifiersEvent event) -> {
            if (event.getEntity() instanceof Player) {
                event.addModifier(Temperature.Trait.WORLD, new MurkTempModifier());
            }
        });
    }

    // The mod's own burners. They are Cold Sweat's blocks rather than something a pack named, so
    // FogSnuff takes them from here instead of from snuffedDevices; snuffColdSweatDevices says
    // whether it asks at all. The icebox is a hearth too, but a cold one, and is not among them.
    private static final List<String> SNUFFED_DEVICES = List.of(
            "cold_sweat:hearth_bottom",
            "cold_sweat:boiler");

    /** Block ids of the mod's burners the murk smothers. */
    public static List<String> snuffedDevices() {
        return SNUFFED_DEVICES;
    }

    // Slot 0 is the fuel input on a hearth (its only slot) and on a boiler (whose other nine hold the
    // bottles it heats, which are not the murk's to throw out).
    private static final int FUEL_SLOT = 0;

    /**
     * Throw the item out of a hearth's or boiler's fuel slot. True when there was one. The icebox is a
     * hearth too, but a cold one; it is not a listed device and is left alone here regardless.
     *
     * <p>Emptying the slot BEFORE it is marked changed matters: the hearth's setChanged() runs its
     * checkForFuel(), which would otherwise turn the item into fuel on the spot -- the way the coal
     * used to vanish. setItem marks it changed itself, after the slot is already empty.
     */
    public static boolean ejectFuel(Level level, BlockPos pos, BlockEntity be) {
        if (!(be instanceof HearthBlockEntity hearth) || be instanceof IceboxBlockEntity) {
            return false;
        }
        ItemStack fuel = hearth.getItem(FUEL_SLOT);
        if (fuel.isEmpty()) {
            return false;
        }
        Block.popResource(level, pos, fuel.copy());
        hearth.setItem(FUEL_SLOT, ItemStack.EMPTY);
        return true;
    }
}
