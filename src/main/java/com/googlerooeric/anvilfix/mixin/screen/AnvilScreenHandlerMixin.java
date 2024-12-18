package com.googlerooeric.anvilfix.mixin.screen;

import com.googlerooeric.anvilfix.AnvilFix;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.*;
import net.minecraft.text.Text;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.component.DataComponentTypes;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

import java.util.*;

import static net.minecraft.util.math.MathHelper.floor;

@Mixin(AnvilScreenHandler.class)
public abstract class AnvilScreenHandlerMixin
        extends ForgingScreenHandler {


    @Shadow @Final private Property levelCost;

    @Shadow private int repairItemUsage;

    @Shadow private String newItemName;

    public AnvilScreenHandlerMixin(@Nullable ScreenHandlerType<?> type, int syncId, PlayerInventory playerInventory, ScreenHandlerContext context) {
        super(type, syncId, playerInventory, context);
    }

    private static final Map<RegistryKey<Enchantment>, Integer> ENCHANTMENT_COST_MAP = new HashMap<>();
    private static Map<RegistryKey<Enchantment>, List<RegistryKey<Enchantment>>> ENCHANTMENT_COMPATIBILITY_MAP = new HashMap<>();

    static {
        // Populate the map with enchantments and their base costs
        // Example: ENCHANTMENT_COST_MAP.put(Enchantments.SHARPNESS, 2);
        ENCHANTMENT_COST_MAP.put(Enchantments.SHARPNESS, 2);
        ENCHANTMENT_COST_MAP.put(Enchantments.EFFICIENCY, 2);
        ENCHANTMENT_COST_MAP.put(Enchantments.MENDING, 15);
        ENCHANTMENT_COST_MAP.put(Enchantments.UNBREAKING, 3);
        ENCHANTMENT_COST_MAP.put(Enchantments.FORTUNE, 3);
        ENCHANTMENT_COST_MAP.put(Enchantments.FIRE_ASPECT, 4);
        ENCHANTMENT_COST_MAP.put(Enchantments.SWEEPING_EDGE, 2);
        ENCHANTMENT_COST_MAP.put(Enchantments.INFINITY, 8);
        ENCHANTMENT_COST_MAP.put(Enchantments.SILK_TOUCH, 4);
        ENCHANTMENT_COST_MAP.put(Enchantments.THORNS, 2);
        ENCHANTMENT_COST_MAP.put(Enchantments.FLAME, 4);
        ENCHANTMENT_COST_MAP.put(Enchantments.AQUA_AFFINITY, 6);
        ENCHANTMENT_COST_MAP.put(Enchantments.LUCK_OF_THE_SEA, 3);
        ENCHANTMENT_COST_MAP.put(Enchantments.PUNCH, 3);
        ENCHANTMENT_COST_MAP.put(Enchantments.RESPIRATION, 3);
        ENCHANTMENT_COST_MAP.put(Enchantments.KNOCKBACK, 3);
        ENCHANTMENT_COST_MAP.put(Enchantments.LOOTING, 3);
        ENCHANTMENT_COST_MAP.put(Enchantments.LURE, 3);
    }

    private boolean areEnchantmentsIncompatible(RegistryKey<Enchantment> e1, RegistryKey<Enchantment> e2) {
        // Check if the pair exists in the map
        return ENCHANTMENT_COMPATIBILITY_MAP.getOrDefault(e1, Collections.emptyList()).contains(e2);
    }



    /**
     * @author googler_ooeric
     * @reason Fix repairing, renaming, and enchanting.
     */
    @Overwrite
    public void updateResult() {

        ENCHANTMENT_COMPATIBILITY_MAP = new HashMap<>();

        // TODO: jesus fucking christ this code feels so wrong
        // Populate compatibility map, if mendingWorksWithUnbreaking is on, don't add these
        this.context.run((world, pos) -> {
            if(!(world.getGameRules().getBoolean(AnvilFix.MENDING_WORKS_WITH_UNBREAKING))){
                ENCHANTMENT_COMPATIBILITY_MAP.put(Enchantments.MENDING, List.of(Enchantments.UNBREAKING));
                ENCHANTMENT_COMPATIBILITY_MAP.put(Enchantments.UNBREAKING, List.of(Enchantments.MENDING));
            }
        });

        ENCHANTMENT_COMPATIBILITY_MAP.put(Enchantments.SMITE, Arrays.asList(Enchantments.SHARPNESS, Enchantments.BANE_OF_ARTHROPODS));
        ENCHANTMENT_COMPATIBILITY_MAP.put(Enchantments.SHARPNESS, Arrays.asList(Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS));
        ENCHANTMENT_COMPATIBILITY_MAP.put(Enchantments.BANE_OF_ARTHROPODS, Arrays.asList(Enchantments.SMITE, Enchantments.SHARPNESS));



        // Get the input item and initialize the level cost to 0
        ItemStack inputItem = this.input.getStack(0);
        this.levelCost.set(0);
        int totalCost = 0;

        // If the input item is empty, set the output to empty and return
        if (inputItem.isEmpty()) {
            this.output.setStack(0, ItemStack.EMPTY);
            this.levelCost.set(0);
            return;
        }

        // Create a copy of the input item to work on
        ItemStack resultItem = inputItem.copy();

        // Get the enchanting item (second item in the anvil)
        ItemStack enchantingItem = this.input.getStack(1);

        // Get the current enchantments on the input item
        Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> currentEnchants = EnchantmentHelper.getEnchantments(resultItem).getEnchantmentEntries();

        // Reset the repair item usage
        this.repairItemUsage = 0;

        // If the enchanting item isn't empty, handle enchanting/repairing
        if (!enchantingItem.isEmpty()) {

            // Check if the enchanting item and input item are enchanted books
            boolean isEnchantedBook = enchantingItem.isOf(Items.ENCHANTED_BOOK) && enchantingItem.getEnchantments().getSize() != 0;
            boolean isInputItemBook = inputItem.isOf(Items.ENCHANTED_BOOK) && inputItem.getEnchantments().getSize() != 0;


            // If the input item is damageable and can be repaired by the second slot item, handle repairing
            if (resultItem.isDamageable() && resultItem.getItem().canRepair(inputItem, enchantingItem)) {
                int repairCount = 0;
                // Calculate the amount to repair, capped at a quarter of the maximum durability
                int repairAmount = Math.min(resultItem.getDamage(), resultItem.getMaxDamage() / 4);

                // While there's still damage to repair and there are more repairing items available
                while (repairAmount > 0 && repairCount < enchantingItem.getCount()) {

                    int newDamage = resultItem.getDamage() - repairAmount;
                    resultItem.setDamage(newDamage);
                    totalCost += 2; // Linear cost of 2 * material amount
                    repairAmount = Math.min(resultItem.getDamage(), resultItem.getMaxDamage() / 4);
                    repairCount++;
                }
                // Why do we even need to keep repairItemUsage??? It's entirely obsolete, we don't do cost accumulation anymore
                //TODO: get rid of repair count and repair item usage
                this.repairItemUsage = repairCount;
            } else {
                // If the input item isn't a book, and the enchanting item isn't an enchanted book or the input item is not damageable, set the output to empty and return
                if (!(isEnchantedBook || resultItem.getItem() == enchantingItem.getItem() && resultItem.isDamageable()) && !isInputItemBook) {
                    this.output.setStack(0, ItemStack.EMPTY);
                    this.levelCost.set(0);
                    return;
                }

                // Get the enchantments on the enchanting item
                Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> newEnchants = EnchantmentHelper.getEnchantments(enchantingItem).getEnchantmentEntries();


                if (isInputItemBook && isEnchantedBook) // If both the input item and the enchanting item are enchanted books, handle merging
                {
                    Map<RegistryKey<Enchantment>, Integer> book2Enchants = new HashMap<>(newEnchants);
                    for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : currentEnchants) {
                        if (book2Enchants.containsKey(e)) {
                            int currentLevel = currentEnchants.get(e);
                            int book2Level = book2Enchants.get(e);
                            if (currentLevel <= book2Level && currentLevel + 1 <= e.getKey().value().getMaxLevel()) {
                                currentEnchants.add(e, currentLevel + 1);
                                totalCost += ENCHANTMENT_COST_MAP.getOrDefault(e, 2) * (currentLevel + 1);
                            }

                            book2Enchants.remove(e);
                        }
                    }

                    for (Object2IntMap.Entry<RegistryKey<Enchantment>, Integer> entry : book2Enchants.entrySet()) {
                        RegistryKey<Enchantment> enchantment = entry.getKey();
                        Integer level = entry.getValue();
                        boolean compatible = currentEnchants.entrySet().stream()
                                .noneMatch(existingEnchant -> areEnchantmentsIncompatible(existingEnchant.getKey(), enchantment));
                        if (compatible) {
                            currentEnchants.add(entry);
                            totalCost += ENCHANTMENT_COST_MAP.getOrDefault(enchantment, 2) * level;
                        }
                    }
                }
                else // If the input item is not an enchanted book, handle regular enchanting
                {
                    for (RegistryKey<Enchantment> enchantment : newEnchants.keySet()) {
                        if (enchantment == null) continue;

                        int currentLevel = currentEnchants.getOrDefault(enchantment, 0);
                        int newLevel = newEnchants.getOrDefault(enchantment, 0);
                        int finalLevel;

                        // If the current level and new level are the same, increment the final level
                        if (currentLevel <= newLevel && currentLevel < enchantment.getKey().getMaxLevel()) {
                            finalLevel = currentLevel + 1;
                        } else {
                            finalLevel = Math.max(newLevel, currentLevel);
                        }

                        // If enchantment is acceptable on item
                        if (enchantment.isAcceptableItem(inputItem)) {

                            // Check for incompatible enchantments
                            boolean hasIncompatibleEnchantments = currentEnchants.keySet().stream()
                                    .anyMatch(enchant -> areEnchantmentsIncompatible(enchantment, enchant));

                            // If there are no incompatible enchantments, add the enchantment
                            if(!hasIncompatibleEnchantments){
                                if(currentEnchants.containsKey(enchantment) && finalLevel > currentEnchants.get(enchantment)){
                                    totalCost += ENCHANTMENT_COST_MAP.getOrDefault(enchantment, 2) * finalLevel;
                                } else if(!currentEnchants.containsKey(enchantment)){
                                    totalCost += ENCHANTMENT_COST_MAP.getOrDefault(enchantment, 2) * Math.max(finalLevel, newLevel);
                                }
                                // What the fuck???
                                currentEnchants.add(enchantment, Math.max(finalLevel, newLevel));
                            }
                        }
                    }
                }
            }
        }

        // Riiise anddd shiinee, Mister Freeman.
        // Rise and... shine. Not that I... wish to imply you have been... sleeping, on the job.
        // No one is more *deserving* of a rest... and all the effort in the worrrllldd would have gone to waste, until...
        // Well... let's just say your hour has... come again.
        // The right man in the wrong place, can make *all* the diff-erence in the worldd...
        // So, wake up, Mister Freeman... Wake up and, smell the ashesssss

        // If the enchanting item is empty and the new name is null or the same as the input item's name, set the output to empty and return
        if (enchantingItem.isEmpty() && (this.newItemName == null || this.newItemName.equals(inputItem.getName().getString()))){
            this.output.setStack(0, ItemStack.EMPTY);
            this.levelCost.set(0);
            return;
        }

        // If the new name is blank and the input item has a custom name, remove the custom name and add the cost
        if (StringUtils.isBlank(this.newItemName) || this.newItemName == null) {
            if(inputItem.contains(DataComponentTypes.CUSTOM_NAME)){
                totalCost += 2;
                resultItem.remove(DataComponentTypes.CUSTOM_NAME);
            }
        }
        // If the new name is not null and different from the input item's name, set the custom name and add the cost
        else if (this.newItemName != null && !this.newItemName.equals(inputItem.getName().getString())) {
            totalCost += 2; // Renaming always costs 2 xp
            resultItem.set(DataComponentTypes.CUSTOM_NAME, Text.literal(this.newItemName));
        }


        // Set the level cost to the total cost
        this.levelCost.set(totalCost);

        // If the total cost is 0 or less, set the result item to empty
        if (totalCost <= 0) {
            resultItem = ItemStack.EMPTY;
        }

        // If the result item isn't empty, set the repair cost and enchantments
        if (!resultItem.isEmpty()) {
            int repairCost = Math.max(resultItem.getOrDefault(DataComponentTypes.REPAIR_COST, Integer.valueOf(0)).intValue(), enchantingItem.isEmpty() ? 0 : enchantingItem.getOrDefault(DataComponentTypes.REPAIR_COST, Integer.valueOf(0)).intValue());
            resultItem.set(DataComponentTypes.REPAIR_COST, repairCost);
            EnchantmentHelper.set(resultItem, currentEnchants);
        }

        // Set the output stack to the result item and send updates
        this.output.setStack(0, resultItem);
        this.sendContentUpdates();
    }




    /**
     * @author googler_ooeric
     * @reason Make it so repairing doesn't increase cost.
     */
    @Overwrite
    public static int getNextCost(int cost) {
        return cost; // Return the cost as it is
    }

}