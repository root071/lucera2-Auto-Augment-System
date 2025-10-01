package services.autoaugment;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import l2.commons.configuration.ExProperties;
import l2.gameserver.handler.items.IItemHandler;
import l2.gameserver.handler.items.ItemHandler;
import l2.gameserver.handler.items.RefineryHandler;
import l2.gameserver.listener.actor.player.OnAnswerListener;
import l2.gameserver.model.Playable;
import l2.gameserver.model.Player;
import l2.gameserver.model.actor.instances.player.ShortCut;
import l2.gameserver.model.items.Inventory;
import l2.gameserver.model.items.ItemContainer;
import l2.gameserver.model.items.ItemInstance;
import l2.gameserver.network.l2.components.SystemMsg;
import l2.gameserver.network.l2.s2c.ConfirmDlg;
import l2.gameserver.network.l2.s2c.InventoryUpdate;
import l2.gameserver.network.l2.s2c.ShortCutRegister;
import l2.gameserver.scripts.ScriptFile;
import l2.gameserver.utils.Location;

import l2.gameserver.data.xml.holder.VariationGroupHolder;
import l2.gameserver.templates.item.support.VariationGroupData;

public final class AutoAugment implements ScriptFile, IItemHandler {

    // === Config ===
    private static Set<Integer> LIFE_STONES = new LinkedHashSet<>(Arrays.asList(
            8723,8724,8725,8726,8727,8728,8729,8730,8731,8732,8733,8734,8735,8736,8737,8738,8739,8740,8741,8742,8743,8744,8745,8746,8747,8748,8749,8750,8751,8752,8753,8754,8755,8756,8757,8758,8759,8760,8761,8762
    ));
    private static boolean UI_CONFIRM = true;
    private static String UI_CONFIRM_TEXT = "Your weapon already has a good augment. Overwrite it?";
    private static boolean GOOD_SKILL_ANY = true;
    private static Set<String> GOOD_STAT_WHITELIST =
            new LinkedHashSet<>(Arrays.asList("STR","CON","DEX","INT","WIT","MEN"));
    private static Set<Integer> GOOD_OPTION_WHITELIST = new LinkedHashSet<>();
    private static boolean GEMLESS = false;
    private static boolean LOG_DEBUG=false;

    // === Internals ===
    private static final ConcurrentHashMap<Integer,Object> LOCKS = new ConcurrentHashMap<>();
    private static final String CHAT_PREFIX = "[AutoAugment]";

    @Override public void onLoad() {
        loadConfig();
        try { ItemHandler.getInstance().registerItemHandler(this); } catch (Throwable t) {
            System.out.println("AutoAugment: registerItemHandler failed: "+t);
        }
        dlog("Loaded.");
    }
    @Override public void onReload() {}
    @Override public void onShutdown() {}

    // === IItemHandler ===
    @Override
    public boolean useItem(Playable playable, ItemInstance lifeStone, boolean ctrl) {
        if (!(playable instanceof Player)) return false;
        Player p = (Player) playable;
        if (lifeStone == null) return false;
        if (!LIFE_STONES.contains(lifeStone.getItemId())) return false;

        ItemInstance weapon = getEquippedWeapon(p);
        if (weapon==null) { pm(p,"No weapon equipped."); return false; }
        if (!isAugmentable(weapon)) { pm(p,"This weapon cannot be augmented."); return false; }

        Object lock = LOCKS.computeIfAbsent(p.getObjectId(), k->new Object());
        synchronized (lock) {
            if (!recheck(p, weapon)) return false;

            boolean hasAug = hasAugmentation(weapon);
            boolean good = hasAug && isGoodAugment(weapon);

            // винаги чистим; питаме само ако е „хубав“
            if (hasAug && good && UI_CONFIRM) {
                ConfirmDlg dlg = new ConfirmDlg(SystemMsg.S1, 15000);
                dlg.addString(UI_CONFIRM_TEXT);
                p.ask(dlg, new OnAnswerListener() {
                    @Override public void sayYes() {
                        if (!recheck(p, weapon)) return;
                        ensureUnaugmented(p, weapon);
                        doRefineViaCore(p, weapon, lifeStone);
                    }
                    @Override public void sayNo() { /* cancel */ }
                });
                return true;
            }

            ensureUnaugmented(p, weapon);              // твърдо премахване
            doRefineViaCore(p, weapon, lifeStone);     // нов аугмент
        }
        return true;
    }

    @Override public void dropItem(Player player, ItemInstance item, long count, Location loc) { /* not used */ }
    @Override public boolean pickupItem(Playable playable, ItemInstance item) { return false; /* not used */ }
    @Override public int[] getItemIds() { return LIFE_STONES.stream().mapToInt(Integer::intValue).toArray(); }

    // === Refine ===
    private static void doRefineViaCore(Player p, ItemInstance weapon, ItemInstance lifeStone) {
        if (!recheck(p, weapon)) return;

        // гаранция: чисто преди refine
        if (hasAugmentation(weapon)) ensureUnaugmented(p, weapon);

        int beforeA = getVarStat(weapon, true), beforeB = getVarStat(weapon, false);

        VariationGroupData fee = resolveFeeFor(weapon, lifeStone);
        if (fee == null) { pm(p, "This Life Stone is not allowed for this weapon."); return; }
        final int gemId = fee.getGemstoneItemId();
        final long gemNeed = fee.getGemstoneItemCnt();

        ItemContainer inv = p.getInventory();
        if (inv == null) { pm(p,"Inventory error."); return; }

        long haveGemsReal = inv.getCountOf(gemId);
        long haveLSReal   = inv.getCountOf(lifeStone.getItemId());
        long injected = 0;

        if (GEMLESS && haveGemsReal < gemNeed) {
            injected = gemNeed - haveGemsReal;
            addItemSilent(p, gemId, injected);
        }

        long gemsBefore = inv.getCountOf(gemId);
        long lsBefore   = inv.getCountOf(lifeStone.getItemId());
        if (gemsBefore < gemNeed || lsBefore < 1) {
            pm(p, "Not enough materials.");
            if (injected > 0) removeItemSilent(p, gemId, injected);
            return;
        }

        ItemInstance gemstones = inv.getItemByItemId(gemId);
        try {
            RefineryHandler.getInstance().onRequestRefine(p, weapon, lifeStone, gemstones, gemNeed);
        } catch (Throwable t) {
            pm(p, "Core refine call failed.");
            if (injected > 0) removeItemSilent(p, gemId, injected);
            return;
        }

        int afterA = getVarStat(weapon, true), afterB = getVarStat(weapon, false);
        long gemsAfter = inv.getCountOf(gemId);
        long lsAfter   = inv.getCountOf(lifeStone.getItemId());
        boolean consumed = (gemsAfter < gemsBefore) && (lsAfter < lsBefore);
        boolean changed  = (afterA != beforeA) || (afterB != beforeB);

        if (consumed || changed) {
            try { p.sendPacket(SystemMsg.THE_ITEM_WAS_SUCCESSFULLY_AUGMENTED); } catch (Throwable ignored) {}
            try { p.sendPacket(new InventoryUpdate().addModifiedItem(weapon)); } catch (Throwable ignored) {}
            pm(p, "Done.");
        } else {
            pm(p, "Refine rejected by core.");
            if (injected > 0) {
                long stillAdded = Math.max(0, inv.getCountOf(gemId) - haveGemsReal);
                if (stillAdded > 0) removeItemSilent(p, gemId, Math.min(injected, stillAdded));
            }
        }
    }

    // === Unaugment: първо core cancel, после принудително ===
    private static void ensureUnaugmented(Player p, ItemInstance w) {
        if (!hasAugmentation(w)) return;

        // 1) официално сваляне (плаща адена)
        try { RefineryHandler.getInstance().onRequestCancelRefine(p, w); } catch (Throwable ignored) {}

        if (!hasAugmentation(w)) {
            try { p.sendPacket(new InventoryUpdate().addModifiedItem(w)); } catch (Throwable ignored) {}
            return;
        }

        // 2) принудително: временно разекипирай, занули, реекипирай, save
        boolean wasEq = isEquipped(w);
        if (wasEq) {
            try { p.getInventory().unEquipItem(w); } catch (Throwable ignored) {}
        }

        // директно извикване на публичните сетъри от ItemInstance (както core-а)
        try { w.setVariationStat1(0); } catch (Throwable ignored) {}
        try { w.setVariationStat2(0); } catch (Throwable ignored) {}
        try { w.save(); } catch (Throwable ignored) {}

        if (wasEq) {
            try { p.getInventory().equipItem(w); } catch (Throwable ignored) {}
        }

        // обнови инвентара и shortcuts за оръжието
        try {
            p.sendPacket(new InventoryUpdate().addModifiedItem(w));
            for (ShortCut sc : p.getAllShortCuts())
                if (sc.getId() == w.getObjectId() && sc.getType() == 1)
                    p.sendPacket(new ShortCutRegister(p, sc));
        } catch (Throwable ignored) {}

        // ако още е маркирано като аугментирано, няма да продължаваме
    }

    // === Variation fee ===
    private static VariationGroupData resolveFeeFor(ItemInstance weapon, ItemInstance lifeStone) {
        try {
            List<VariationGroupData> list = VariationGroupHolder.getInstance().getDataForItemId(weapon.getItemId());
            if (list == null || list.isEmpty()) return null;
            for (VariationGroupData g : list)
                if (g.getMineralItemId() == lifeStone.getItemId()) return g;
        } catch (Throwable ignored) {}
        return null;
    }

    // === Good-augment detection ===
    private static boolean hasAugmentation(ItemInstance w) {
        return getVarStat(w,true)!=0 || getVarStat(w,false)!=0;
    }

    private static boolean isGoodAugment(ItemInstance w) {
        int a = getVarStat(w, true);
        int b = getVarStat(w, false);
        int[] opts = (a>0 && b>0) ? new int[]{a,b} : (a>0 ? new int[]{a} : (b>0 ? new int[]{b} : new int[0]));
        if (opts.length == 0) return false;

        // 1) Явен whitelist по ID
        for (int id : opts) if (GOOD_OPTION_WHITELIST.contains(id)) return true;

        // 2) Skill или Trigger (Chance) => "добър"
        try {
            Class<?> holderCls = Class.forName("l2.gameserver.data.xml.holder.OptionDataHolder");
            Object holder = holderCls.getMethod("getInstance").invoke(null);
            for (int id : opts) {
                Object tmpl = holderCls.getMethod("getTemplate", int.class).invoke(holder, id);
                if (tmpl == null) continue;

                // skills (Active/Passive)
                try {
                    Object skills = tmpl.getClass().getMethod("getSkills").invoke(tmpl); // List<Skill>
                    if (skills instanceof java.util.Collection && !((java.util.Collection<?>)skills).isEmpty())
                        return true;
                } catch (Throwable ignored) {}

                // triggers (Chance)
                try {
                    Object triggers = tmpl.getClass().getMethod("getTriggers").invoke(tmpl); // List<TriggerInfo>
                    if (triggers instanceof java.util.Collection && !((java.util.Collection<?>)triggers).isEmpty())
                        return true;
                } catch (Throwable ignored) {}

                // 3) По избор: стат ключови думи (остави ако ти трябва)
                if (!GOOD_STAT_WHITELIST.isEmpty()) {
                    String s = String.valueOf(tmpl).toUpperCase();
                    for (String k : GOOD_STAT_WHITELIST) if (s.contains(k)) return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }


    // === Helpers ===
    private static int getVarStat(ItemInstance w, boolean first) {
        try { return first ? w.getVariationStat1() : w.getVariationStat2(); }
        catch (Throwable ignored) { return 0; }
    }
    private static boolean isEquipped(ItemInstance w) {
        try { return w.isEquipped(); } catch (Throwable ignored) { return false; }
    }
    private static ItemInstance getEquippedWeapon(Player p) {
        Inventory inv = p.getInventory();
        if (inv==null) return null;
        ItemInstance r = inv.getPaperdollItem(Inventory.PAPERDOLL_RHAND);
        if (r==null) r = inv.getPaperdollItem(Inventory.PAPERDOLL_LRHAND);
        return r;
    }
    private static boolean isAugmentable(ItemInstance w) {
        try { if (w.isHeroWeapon() || w.isCursed() || w.isShadowItem()) return false; } catch (Throwable ignored) {}
        try { if (!w.isWeapon() && !w.isEquipable()) return false; } catch (Throwable ignored) {}
        // разрешено ли е от VariationGroup за този itemId
        try {
            java.util.List<l2.gameserver.templates.item.support.VariationGroupData> list =
                    l2.gameserver.data.xml.holder.VariationGroupHolder.getInstance().getDataForItemId(w.getItemId());
            return list != null && !list.isEmpty();
        } catch (Throwable ignored) {}
        return true;
    }

    private static boolean recheck(Player p, ItemInstance w) {
        if (p == null || w == null) return false;
        try { if (w.getOwnerId() != p.getObjectId()) return false; } catch (Throwable ignored) {}
        // Няма isDestroyed()/isLocked() в твоя клас → не ги проверяваме
        return true;
    }

    private static void addItemSilent(Player p, int itemId, long count) {
        try {
            Class.forName("l2.gameserver.utils.ItemFunctions")
                    .getMethod("addItem", Player.class, int.class, long.class, boolean.class)
                    .invoke(null, p, itemId, count, Boolean.FALSE);
        } catch (Throwable ignored) {}
    }
    private static void removeItemSilent(Player p, int itemId, long count) {
        try { p.getInventory().destroyItemByItemId(itemId, count); } catch (Throwable ignored) {}
    }

    private static void pm(Player p, String msg) {
    }
    private static void dlog(String s){ if (LOG_DEBUG) System.out.println("AutoAugment: "+s); }

    // === Config loader ===
    private static void loadConfig() {
        try {
            File f = new File("config/AutoAugment.properties");
            if (!f.exists()) { dlog("Config not found. Using defaults."); return; }
            ExProperties p = new ExProperties();
            p.load(f);

            LIFE_STONES = parseIntSet(p.getProperty("LifeStones", joinInts(LIFE_STONES)));
            UI_CONFIRM = bool(p.getProperty("UI_Confirm","true"));
            UI_CONFIRM_TEXT = p.getProperty("UI_Confirm_Text", UI_CONFIRM_TEXT);

            GOOD_SKILL_ANY = bool(p.getProperty("GoodAugment_SkillAny","true"));
            GOOD_STAT_WHITELIST = parseStrSet(p.getProperty("GoodAugment_StatWhitelist", joinStr(GOOD_STAT_WHITELIST)));
            GOOD_OPTION_WHITELIST = parseIntSet(p.getProperty("GoodAugment_OptionWhitelist",""));

            GEMLESS = bool(p.getProperty("Gemless","false"));
            LOG_DEBUG = bool(p.getProperty("LogDebug","false"));
        } catch (Throwable t) {
            System.out.println("AutoAugment: config load failed, using defaults. "+t);
        }
    }
    private static boolean bool(String v){ return "true".equalsIgnoreCase(v); }
    private static Set<Integer> parseIntSet(String s){
        if (s==null||s.trim().isEmpty()) return new LinkedHashSet<>();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x->!x.isEmpty())
                .map(Integer::parseInt).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    private static Set<String> parseStrSet(String s){
        if (s==null||s.trim().isEmpty()) return new LinkedHashSet<>();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x->!x.isEmpty())
                .map(String::toUpperCase).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    private static String joinInts(Collection<Integer> c){ return c.stream().map(String::valueOf).collect(Collectors.joining(",")); }
    private static String joinStr(Collection<String> c){ return String.join(",", c); }
}
