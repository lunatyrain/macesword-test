package dev.macesword;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MaceSwordPlugin extends JavaPlugin implements Listener {

    private NamespacedKey markerKey;
    private NamespacedKey recipeKey;

    /** Sword + mace enchants (plus the shared ones). Conflicts between them are ignored. */
    private static final Set<Enchantment> ALLOWED = Set.of(
            // sword
            Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS,
            Enchantment.FIRE_ASPECT, Enchantment.LOOTING, Enchantment.SWEEPING_EDGE,
            Enchantment.KNOCKBACK,
            // mace
            Enchantment.DENSITY, Enchantment.BREACH, Enchantment.WIND_BURST,
            // shared
            Enchantment.UNBREAKING, Enchantment.MENDING, Enchantment.VANISHING_CURSE
    );

    @Override
    public void onEnable() {
        markerKey = new NamespacedKey(this, "mace_sword");
        recipeKey = new NamespacedKey(this, "mace_sword_recipe");

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createMaceSword());
        recipe.shape(" M ", "SCS", " D ");
        recipe.setIngredient('M', Material.HEAVY_CORE);
        recipe.setIngredient('S', Material.ANCIENT_DEBRIS);
        recipe.setIngredient('C', Material.DRAGON_EGG);
        recipe.setIngredient('D', Material.DIAMOND_SWORD);
        Bukkit.addRecipe(recipe);

        // Block the regular mace craft (heavy core + breeze rod)
        Bukkit.removeRecipe(NamespacedKey.minecraft("mace"));

        // Unlock for anyone already online (e.g. plugin added while the server was running)
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.discoverRecipe(recipeKey);
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
    }

    public boolean isMaceSword(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    public ItemStack createMaceSword() {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();

        // Looks like a netherite sword
        meta.setItemModel(NamespacedKey.minecraft("netherite_sword"));

        meta.displayName(MiniMessage.miniMessage().deserialize(
                "<!italic><gradient:#FF69B4:#FFFFFF>Mace Sword</gradient>"));
        meta.lore(List.of(Component.text("Splicing data like this is illegal you know.")));
        meta.setUnbreakable(true);

        // Netherite sword stats: 8 damage total (1 base + 7), 1.6 speed (4 - 2.4)
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                new NamespacedKey(this, "mace_sword_damage"), 7.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                new NamespacedKey(this, "mace_sword_speed"), -2.4,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));

        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Groups where only one enchant may be on the item at a time. */
    private static final List<Set<Enchantment>> EXCLUSIVE_GROUPS = List.of(
            Set.of(Enchantment.BREACH, Enchantment.DENSITY),
            Set.of(Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS)
    );

    /** Enchants the enchanting table can roll on the Mace Sword (sword + mace). */
    private static final List<Enchantment> TABLE_POOL = List.of(
            Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS,
            Enchantment.FIRE_ASPECT, Enchantment.LOOTING, Enchantment.SWEEPING_EDGE,
            Enchantment.KNOCKBACK, Enchantment.UNBREAKING,
            Enchantment.DENSITY, Enchantment.BREACH
    );

    private final Map<UUID, Integer> enchantCount = new HashMap<>();

    private long seed(Player p, int button, int cost) {
        int n = enchantCount.getOrDefault(p.getUniqueId(), 0);
        return p.getUniqueId().getMostSignificantBits() ^ (n * 7919L) ^ (button * 131L) ^ (cost * 1000003L);
    }

    private static int levelFor(Enchantment ench, int cost) {
        // Table only: no Sharpness V (still possible with books in an anvil)
        int max = ench.equals(Enchantment.SHARPNESS) ? 4 : ench.getMaxLevel();
        int lvl = (int) Math.round(cost / 30.0 * max);
        return Math.max(1, Math.min(max, lvl));
    }

    private static boolean conflicts(Map<Enchantment, Integer> present, Enchantment ench) {
        for (Set<Enchantment> group : EXCLUSIVE_GROUPS) {
            if (!group.contains(ench)) continue;
            for (Enchantment other : group) {
                if (!other.equals(ench) && present.containsKey(other)) return true;
            }
        }
        return false;
    }

    /** Rolls sword + mace enchants for the table. The first entry is the one shown as the hint. */
    private static Map<Enchantment, Integer> rollEnchants(Random r, int cost) {
        List<Enchantment> pool = new ArrayList<>(TABLE_POOL);
        Collections.shuffle(pool, r);
        Map<Enchantment, Integer> out = new LinkedHashMap<>();
        out.put(pool.get(0), levelFor(pool.get(0), cost));
        double chance = (cost + 1) / 50.0;
        for (Enchantment c : pool.subList(1, pool.size())) {
            if (r.nextDouble() >= chance) break;
            if (conflicts(out, c)) continue;
            out.put(c, levelFor(c, cost));
            chance /= 2;
        }
        return out;
    }

    /** Enchanting table previews for the Mace Sword only. */
    @EventHandler
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        if (!isMaceSword(e.getItem())) return;
        EnchantmentOffer[] offers = e.getOffers();
        for (int i = 0; i < offers.length; i++) {
            EnchantmentOffer offer = offers[i];
            if (offer == null || offer.getCost() <= 0) continue;
            Map<Enchantment, Integer> roll = rollEnchants(
                    new Random(seed(e.getEnchanter(), i, offer.getCost())), offer.getCost());
            Enchantment first = roll.keySet().iterator().next();
            offer.setEnchantment(first);
            offer.setEnchantmentLevel(roll.get(first));
        }
    }

    /** Applies sword + mace enchants when enchanting the Mace Sword at a table. */
    @EventHandler
    public void onEnchant(EnchantItemEvent e) {
        if (!isMaceSword(e.getItem())) return;
        Player p = e.getEnchanter();
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        add.clear();
        add.putAll(rollEnchants(new Random(seed(p, e.whichButton(), e.getExpLevelCost())), e.getExpLevelCost()));
        enchantCount.merge(p.getUniqueId(), 1, Integer::sum);
    }

    private static boolean conflicts(ItemMeta meta, Enchantment ench) {
        for (Set<Enchantment> group : EXCLUSIVE_GROUPS) {
            if (!group.contains(ench)) continue;
            for (Enchantment other : group) {
                if (!other.equals(ench) && meta.hasEnchant(other)) return true;
            }
        }
        return false;
    }

    /** Dropped Mace Swords can't be destroyed by cactus, lava, fire, or explosions. */
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item entity = e.getEntity();
        if (isMaceSword(entity.getItemStack())) {
            entity.setInvulnerable(true);
        }
    }

    /** Backup for items that were already in the world before the plugin loaded. */
    @EventHandler
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item item && isMaceSword(item.getItemStack())
                && e.getCause() != EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemCombust(EntityCombustEvent e) {
        if (e.getEntity() instanceof Item drop && isMaceSword(drop.getItemStack())) {
            e.setCancelled(true);
        }
    }

    /** Backup: nothing in a crafting grid may produce a normal mace; only the Mace Sword recipe can. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory().getResult();
        if (result != null && result.getType() == Material.MACE && !isMaceSword(result)) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        // Wait a tick so the player's recipe book is ready
        Bukkit.getScheduler().runTaskLater(this, () -> player.discoverRecipe(recipeKey), 20L);
    }

    /** Lets ONLY the Mace Sword combine sword and mace enchants. Breach/Density and Sharpness/Smite/Bane stay mutually exclusive. */
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        ItemStack left = inv.getFirstItem();
        ItemStack right = inv.getSecondItem();
        if (!isMaceSword(left)) {
            guardVanilla(e, left);
            return;
        }
        if (right == null || right.getType().isAir()) return;

        Map<Enchantment, Integer> incoming = new HashMap<>();
        if (right.getItemMeta() instanceof EnchantmentStorageMeta book) {
            incoming.putAll(book.getStoredEnchants());
        } else {
            incoming.putAll(right.getEnchantments());
        }
        if (incoming.isEmpty()) return;

        ItemStack result = left.clone();
        ItemMeta meta = result.getItemMeta();
        int cost = 0;
        boolean changed = false;

        for (Map.Entry<Enchantment, Integer> en : incoming.entrySet()) {
            Enchantment ench = en.getKey();
            if (!ALLOWED.contains(ench)) continue;
            if (conflicts(meta, ench)) continue;
            int current = meta.getEnchantLevel(ench);
            int in = en.getValue();
            int next = (current == in) ? Math.min(in + 1, ench.getMaxLevel()) : Math.max(current, in);
            if (next == current) continue;
            meta.addEnchant(ench, next, true);
            cost += next;
            changed = true;
        }

        if (!changed) {
            e.setResult(null);
            return;
        }
        result.setItemMeta(meta);
        e.setResult(result);
        e.getView().setRepairCost(Math.min(39, Math.max(1, cost)));
    }

    /** Normal maces and swords stay vanilla: block any anvil result with enchants they can't normally have. */
    private void guardVanilla(PrepareAnvilEvent e, ItemStack left) {
        if (left == null) return;
        Material type = left.getType();
        if (type != Material.MACE && !type.name().endsWith("_SWORD")) return;
        ItemStack result = e.getResult();
        if (result == null || result.getType() != type) return;

        ItemStack plain = new ItemStack(type);
        List<Enchantment> list = new ArrayList<>(result.getEnchantments().keySet());
        for (int i = 0; i < list.size(); i++) {
            Enchantment a = list.get(i);
            if (!a.canEnchantItem(plain)) {
                e.setResult(null);
                return;
            }
            for (int j = i + 1; j < list.size(); j++) {
                if (a.conflictsWith(list.get(j))) {
                    e.setResult(null);
                    return;
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player target = null;
        if (args.length > 0) target = Bukkit.getPlayer(args[0]);
        else if (sender instanceof Player p) target = p;
        if (target == null) {
            sender.sendMessage("Usage: /macesword [player]");
            return true;
        }
        target.getInventory().addItem(createMaceSword());
        return true;
    }
}
