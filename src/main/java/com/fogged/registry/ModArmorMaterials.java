package com.fogged.registry;

import java.util.EnumMap;
import java.util.List;

import com.fogged.Fogged;

import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's armor materials. */
public final class ModArmorMaterials {
    public static final DeferredRegister<ArmorMaterial> MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, Fogged.MODID);

    /**
     * The fog walker's copper diving suit. Its one layer is
     * {@code textures/models/armor/fog_diving_layer_1.png}, which the vanilla armor layer draws on the body
     * and arms; the helmet and the canister on the chest are the fog walker's own render layer.
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> FOG_DIVING = MATERIALS.register("fog_diving",
            () -> new ArmorMaterial(
                    Util.make(new EnumMap<>(ArmorItem.Type.class), m -> {
                        m.put(ArmorItem.Type.BOOTS, 2);
                        m.put(ArmorItem.Type.LEGGINGS, 5);
                        m.put(ArmorItem.Type.CHESTPLATE, 6);
                        m.put(ArmorItem.Type.HELMET, 2);
                        m.put(ArmorItem.Type.BODY, 5);
                    }),
                    12, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(Items.COPPER_INGOT),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_diving"))),
                    0.0F, 0.0F));

    public static void register(IEventBus modEventBus) {
        MATERIALS.register(modEventBus);
    }

    private ModArmorMaterials() {}
}
