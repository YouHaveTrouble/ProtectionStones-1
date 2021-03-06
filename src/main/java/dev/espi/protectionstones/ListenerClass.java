/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.espi.protectionstones;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.espi.protectionstones.event.PSCreateEvent;
import dev.espi.protectionstones.event.PSRemoveEvent;
import dev.espi.protectionstones.utils.UUIDCache;
import dev.espi.protectionstones.utils.WGUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ListenerClass implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUIDCache.removeUUID(p.getUniqueId());
        UUIDCache.removeName(p.getName());
        UUIDCache.storeUUIDNamePair(p.getUniqueId(), p.getName());

        // allow worldguard to resolve all UUIDs to names
        Bukkit.getScheduler().runTaskAsynchronously(ProtectionStones.getInstance(), () -> UUIDCache.storeWGProfile(p.getUniqueId(), p.getName()));

        PSPlayer psp = PSPlayer.fromPlayer(p);

        // if by default, players should have protection block placement toggled off
        if (ProtectionStones.getInstance().getConfigOptions().defaultProtectionBlockPlacementOff) {
            ProtectionStones.toggleList.add(p.getUniqueId());
        }

        // tax join message
        if (ProtectionStones.getInstance().getConfigOptions().taxEnabled && ProtectionStones.getInstance().getConfigOptions().taxMessageOnJoin) {
            Bukkit.getScheduler().runTaskAsynchronously(ProtectionStones.getInstance(), () -> {
                int amount = 0;
                for (PSRegion psr : psp.getTaxEligibleRegions()) {
                    for (PSRegion.TaxPayment tp : psr.getTaxPaymentsDue()) {
                        amount += tp.getAmount();
                    }
                }

                if (amount != 0) {
                    PSL.msg(psp, PSL.TAX_JOIN_MSG_PENDING_PAYMENTS.msg().replace("%money%", "" + amount));
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        BlockHandler.createPSRegion(e);
    }

    // helper method for breaking protection blocks
    private boolean playerBreakProtection(Player p, PSRegion r) {
        PSProtectBlock blockOptions = r.getTypeOptions();

        // check for destroy permission
        if (!p.hasPermission("protectionstones.destroy")) {
            PSL.msg(p, PSL.NO_PERMISSION_DESTROY.msg());
            return false;
        }

        // check if player is owner of region
        if (!r.isOwner(p.getUniqueId()) && !p.hasPermission("protectionstones.superowner")) {
            PSL.msg(p, PSL.NO_REGION_PERMISSION.msg());
            return false;
        }

        // cannot break region being rented (prevents splitting merged regions, and breaking as tenant owner)
        if (r.getRentStage() == PSRegion.RentStage.RENTING && !p.hasPermission("protectionstones.superowner")) {
            PSL.msg(p, PSL.RENT_CANNOT_BREAK_WHILE_RENTING.msg());
            return false;
        }

        // return protection stone if no drop option is off
        if (blockOptions != null && !blockOptions.noDrop) {
            if (!p.getInventory().addItem(blockOptions.createItem()).isEmpty()) {
                // method will return not empty if item couldn't be added
                if (ProtectionStones.getInstance().getConfigOptions().dropItemWhenInventoryFull) {
                    PSL.msg(p, PSL.NO_ROOM_DROPPING_ON_FLOOR.msg());
                    p.getWorld().dropItem(r.getProtectBlock().getLocation(), blockOptions.createItem());
                } else {
                    PSL.msg(p, PSL.NO_ROOM_IN_INVENTORY.msg());
                    return false;
                }
            }
        }

        // check if removing the region and firing region remove event blocked it
        if (!r.deleteRegion(true, p)) {
            if (!ProtectionStones.getInstance().getConfigOptions().allowMergingHoles) { // side case if the removing creates a hole and those are prevented
                PSL.msg(p, PSL.DELETE_REGION_PREVENTED_NO_HOLES.msg());
            }
            return false;
        }

        PSL.msg(p, PSL.NO_LONGER_PROTECTED.msg());
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent e) {
        // shift-right click block with hand to break
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && !e.isBlockInHand()
                && e.getClickedBlock() != null && ProtectionStones.isProtectBlock(e.getClickedBlock())) {

            PSProtectBlock ppb = ProtectionStones.getBlockOptions(e.getClickedBlock());
            if (ppb.allowShiftRightBreak && e.getPlayer().isSneaking()) {
                if (playerBreakProtection(e.getPlayer(), PSRegion.fromLocation(e.getClickedBlock().getLocation()))) { // successful
                    e.getClickedBlock().setType(Material.AIR);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block pb = e.getBlock();

        PSProtectBlock blockOptions = ProtectionStones.getBlockOptions(pb);

        // check if block broken is protection stone type
        if (blockOptions == null) return;

        // check if that is actually a protection stone block (owns a region)
        if (!ProtectionStones.isProtectBlock(pb)) {
            // prevent silk touching of protection stone blocks (that aren't holding a region)
            if (blockOptions.preventSilkTouch) {
                ItemStack left = p.getInventory().getItemInMainHand();
                ItemStack right = p.getInventory().getItemInOffHand();
                if (!left.containsEnchantment(Enchantment.SILK_TOUCH) && !right.containsEnchantment(Enchantment.SILK_TOUCH)) {
                    return;
                }
                e.setDropItems(false);
            }
            return;
        }

        PSRegion r = PSRegion.fromLocation(pb.getLocation());

        // break protection
        if (playerBreakProtection(p, r)) { // successful
            e.setDropItems(false);
            e.setExpToDrop(0);
        } else { // unsuccessful
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent e) {
        // prevent protect block item to be smelt
        Furnace f = (Furnace) e.getBlock().getState();
        PSProtectBlock options = ProtectionStones.getBlockOptions(e.getSource().getType().toString());
        if (options != null && !options.allowSmeltItem && ProtectionStones.isProtectBlockItem(e.getSource(), options.restrictObtaining)) {
            if (f.getCookTime() != 0) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFurnaceBurnItem(FurnaceBurnEvent e) {
        // prevent protect block item to be smelt
        Furnace f = (Furnace) e.getBlock().getState();
        if (f.getInventory().getSmelting() != null) {
            PSProtectBlock options = ProtectionStones.getBlockOptions(f.getInventory().getSmelting().getType().toString());
            if (options != null && !options.allowSmeltItem && ProtectionStones.isProtectBlockItem(f.getInventory().getSmelting(), options.restrictObtaining)) {
                e.setCancelled(true);
            }
        }
    }

    // -=-=-=- block changes to protection block related events -=-=-=-

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketEmptyEvent e) {
        Block clicked = e.getBlockClicked();
        BlockFace bf = e.getBlockFace();
        Block check = clicked.getWorld().getBlockAt(clicked.getX() + e.getBlockFace().getModX(), clicked.getY() + bf.getModY(), clicked.getZ() + e.getBlockFace().getModZ());
        if (ProtectionStones.isProtectBlock(check)) {
            e.setCancelled(true);
            // fix for dumb head texture changing
            // Bukkit.getScheduler().runTask(ProtectionStones.getInstance(), () -> MiscUtil.setHeadType(ProtectionStones.getBlockOptions(check).type, check));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent e) {
        if (ProtectionStones.isProtectBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        if (ProtectionStones.isProtectBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent e) {
        if (ProtectionStones.isProtectBlock(e.getToBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        if (ProtectionStones.isProtectBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPhysicsEvent(BlockPhysicsEvent e) {
        if (ProtectionStones.isProtectBlock(e.getBlock()) || ProtectionStones.isProtectBlock(e.getSourceBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent e) {
        if (ProtectionStones.isProtectBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent e) {
        // unfortunately, the below fix does not really work because Spigot only triggers for the source block, despite
        // what the documentation says: https://hub.spigotmc.org/javadocs/spigot/org/bukkit/event/block/BlockDropItemEvent.html

        // we want to replace protection blocks that have their protection block broken (ex. signs, banners)
        // the block may not exist anymore, and so we have to recreate the isProtectBlock method here
        BlockState bs = e.getBlockState();
        if (!ProtectionStones.isProtectBlockType(bs.getType().toString())) return;

        RegionManager rgm = WGUtils.getRegionManagerWithWorld(bs.getWorld());
        if (rgm == null) return;

        // check if the block is a source block
        ProtectedRegion br = rgm.getRegion(WGUtils.createPSID(bs.getLocation()));
        if (!ProtectionStones.isPSRegion(br) && PSMergedRegion.getMergedRegion(bs.getLocation()) == null) return;

        PSRegion r = PSRegion.fromLocation(bs.getLocation());
        if (r == null) return;

        // puts the block back
        r.unhide();
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        pistonUtil(e.getBlocks(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        pistonUtil(e.getBlocks(), e);
    }

    private void pistonUtil(List<Block> pushedBlocks, BlockPistonEvent e) {
        for (Block b : pushedBlocks) {
            PSProtectBlock cpb = ProtectionStones.getBlockOptions(b);
            if (cpb != null && ProtectionStones.isProtectBlock(b) && cpb.preventPistonPush) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        explodeUtil(e.blockList(), e.getBlock().getLocation().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        explodeUtil(e.blockList(), e.getLocation().getWorld());
    }

    private void explodeUtil(List<Block> blockList, World w) {
        // loop through exploded blocks
        for (int i = 0; i < blockList.size(); i++) {
            Block b = blockList.get(i);

            if (ProtectionStones.isProtectBlock(b)) {
                String id = WGUtils.createPSID(b.getLocation());

                PSProtectBlock blockOptions = ProtectionStones.getBlockOptions(b);

                // remove protection block from exploded list if prevent_explode is enabled
                blockList.remove(i);
                i--;

                // if allow explode
                if (!blockOptions.preventExplode) {
                    b.setType(Material.AIR); // manually set to air
                    // manually add drop
                    if (!blockOptions.noDrop) {
                        b.getWorld().dropItem(b.getLocation(), blockOptions.createItem());
                    }
                    // remove region from worldguard if destroy_region_when_explode is enabled
                    if (blockOptions.destroyRegionWhenExplode) {
                        ProtectionStones.removePSRegion(w, id);
                    }
                }
            }
        }
    }

    // check player teleporting into region behaviour
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // we only want plugin triggered teleports, ignore natural teleportation
        if (event.getCause() == TeleportCause.ENDER_PEARL || event.getCause() == TeleportCause.CHORUS_FRUIT) return;

        if (event.getPlayer().hasPermission("protectionstones.tp.bypassprevent")) return;

        WorldGuardPlugin wg = WorldGuardPlugin.inst();
        RegionManager rgm = WGUtils.getRegionManagerWithWorld(event.getTo().getWorld());
        BlockVector3 v = BlockVector3.at(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ());

        // check if player can teleport into region (no region with preventTeleportIn = true)
        ApplicableRegionSet regions = rgm.getApplicableRegions(v);
        if (regions.getRegions().isEmpty()) return;
        boolean foundNoTeleport = false;
        for (ProtectedRegion r : regions) {
            String f = r.getFlag(FlagHandler.PS_BLOCK_MATERIAL);
            if (f != null && ProtectionStones.getBlockOptions(f) != null && ProtectionStones.getBlockOptions(f).preventTeleportIn)
                foundNoTeleport = true;
            if (r.getOwners().contains(wg.wrapPlayer(event.getPlayer()))) return;
        }

        if (foundNoTeleport) {
            PSL.msg(event.getPlayer(), PSL.REGION_CANT_TELEPORT.msg());
            event.setCancelled(true);
        }
    }

    // -=-=-=- player defined events -=-=-=-

    private void execEvent(String action, CommandSender s, String player, PSRegion region) {
        if (player == null) player = "";

        // split action_type: action
        String[] sp = action.split(": ");
        if (sp.length == 0) return;

        StringBuilder act = new StringBuilder(sp[1]);
        for (int i = 2; i < sp.length; i++) act.append(": ").append(sp[i]); // add anything extra that has a colon

        act = new StringBuilder(act.toString()
                .replace("%player%", player)
                .replace("%world%", region.getWorld().getName())
                .replace("%region%", region.getName() == null ? region.getId() : region.getName() + " (" + region.getId() + ")")
                .replace("%block_x%", region.getProtectBlock().getX() + "")
                .replace("%block_y%", region.getProtectBlock().getY() + "")
                .replace("%block_z%", region.getProtectBlock().getZ() + ""));

        switch (sp[0]) {
            case "player_command":
                if (s != null) Bukkit.getServer().dispatchCommand(s, act.toString());
                break;
            case "console_command":
                Bukkit.getServer().dispatchCommand(Bukkit.getServer().getConsoleSender(), act.toString());
                break;
            case "message":
                if (s != null) s.sendMessage(ChatColor.translateAlternateColorCodes('&', act.toString()));
                break;
            case "global_message":
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(ChatColor.translateAlternateColorCodes('&', act.toString()));
                }
                ProtectionStones.getPluginLogger().info(ChatColor.translateAlternateColorCodes('&', act.toString()));
                break;
            case "console_message":
                ProtectionStones.getPluginLogger().info(ChatColor.translateAlternateColorCodes('&', act.toString()));
                break;
        }
    }

    @EventHandler
    public void onPSCreate(PSCreateEvent event) {
        if (event.isCancelled()) return;
        if (!event.getRegion().getTypeOptions().eventsEnabled) return;

        // run on next tick (after the region is created to allow for edits to the region)
        Bukkit.getServer().getScheduler().runTask(ProtectionStones.getInstance(), () -> {
            // run custom commands (in config)
            for (String action : event.getRegion().getTypeOptions().regionCreateCommands) {
                execEvent(action, event.getPlayer(), event.getPlayer().getName(), event.getRegion());
            }
        });
    }

    @EventHandler
    public void onPSRemove(PSRemoveEvent event) {
        if (event.isCancelled()) return;
        if (event.getRegion().getTypeOptions() == null) return;
        if (!event.getRegion().getTypeOptions().eventsEnabled) return;

        // run custom commands (in config)
        for (String action : event.getRegion().getTypeOptions().regionDestroyCommands) {
            if (event.getPlayer() == null) {
                execEvent(action, null, null, event.getRegion());
            } else {
                execEvent(action, event.getPlayer(), event.getPlayer().getName(), event.getRegion());
            }
        }
    }

}
