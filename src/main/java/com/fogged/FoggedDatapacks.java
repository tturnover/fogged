package com.fogged;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import com.mojang.serialization.MapCodec;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The datapacks the mod ships inside itself, offered in the pack list like any other.
 *
 * <p>Five, and between them everything the murk used to turn over from the {@code transforms} config
 * list, which is now empty: <b>Grass Scourch</b> (every grassed block back to the bare ground under
 * it), <b>Moss Scourch</b> (moss blocks, prismoss, mossy stone and brick with their cuts), <b>Copper
 * Oxidation</b> (every unwaxed copper block weathered the whole way through), <b>Silverfish
 * Suffocation</b> (infested blocks back to the plain stone the silverfish were hiding in) and <b>Coal
 * Remover</b> (coal ore back to its own rock, blocks of coal taken outright).
 *
 * <p>Two more hold tags rather than recipes: <b>Plant Scouring</b> is what the murk wilts and
 * <b>Device Snuffing</b> is the burners it puts out, filling {@code #fogged:scoured} and
 * {@code #fogged:snuffed_devices}. The code asks those tags whether or not a config list names them,
 * so what the murk does by itself is data a pack can replace, and the lists are only what one world
 * adds on top.
 *
 * <p>Those two have no switch in {@code [datapacks]}, unlike the five made of recipes: a tag file
 * cannot carry a condition, and a switch that worked around it in code would have to empty the whole
 * tag -- taking another mod's entries with it, which are not this mod's to refuse. The pack list is
 * the honest place to say no to them.
 *
 * <p>Each is a set, answered yes or no: either the murk strips the grass off what it covers or it does
 * not. As config lines that was a dozen entries to delete one at a time to say no; as a pack it is one
 * switch. It is also the mod eating its own cooking -- they are the same
 * {@code fogged:murk_transform} recipes any other mod would write (see {@link MurkTransformRecipe}).
 *
 * <p>The files live at {@code datapacks/<name>} in the mod jar.
 */
@EventBusSubscriber(modid = Fogged.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class FoggedDatapacks {
    private FoggedDatapacks() {}

    // The condition each pack's recipes carry, so that the switch in [datapacks] and the pack list
    // itself say the same thing (see PackEnabledCondition).
    private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
            DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, Fogged.MODID);

    static {
        CONDITIONS.register("pack_enabled", () -> PackEnabledCondition.CODEC);
    }

    public static void register(IEventBus modEventBus) {
        CONDITIONS.register(modEventBus);
    }

    private static final String[] PACKS = {
            "grass_scourch", "moss_scourch", "copper_oxidation", "silverfish_suffocation", "coal_remover",
            "plant_scouring", "device_snuffing",
    };

    @SubscribeEvent
    static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        for (String name : PACKS) {
            // Not alwaysActive: the point of moving these out of the config was to leave somewhere to
            // turn them off. A world that has never seen one takes it up the way it takes up any new
            // pack; one where it has been switched off keeps it off.
            event.addPackFinders(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "datapacks/" + name),
                    PackType.SERVER_DATA, Component.translatable("fogged.datapack." + name),
                    PackSource.BUILT_IN, false, Pack.Position.TOP);
        }
    }
}
