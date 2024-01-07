package net.pevent.tmcds;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Tmcds extends JavaPlugin implements CommandExecutor, Listener {
    private final Map<String, Long> startTimes = new HashMap<>();
    private final Map<String, Long> pausedTimes = new HashMap<>();
    private final Map<String, Boolean> isWorking = new HashMap<>();
    private File playerDataFile;
    private FileConfiguration playerData;

    @Override
    public void onEnable() {
        createPluginFolder();
        createPlayerDataFile();
        loadPlayerData();

        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (String playerName : startTimes.keySet()) {
                if (isWorking.getOrDefault(playerName, false)) {
                    long currentTime = System.currentTimeMillis();
                    long lastActivityTime = pausedTimes.containsKey(playerName) ? pausedTimes.get(playerName) : startTimes.get(playerName);

                    if (currentTime - lastActivityTime >= 5 * 60 * 1000) {
                        pauseWorking(playerName);
                        Player player = Bukkit.getServer().getPlayer(playerName);
                        if (player != null && player.isOnline()) {
                            player.sendMessage(ChatColor.YELLOW + "5分間動かなかったため、勤務を一時停止しました。");
                        }
                    }
                }
            }
        }, 0, 20 * 60);  // 20 ticks = 1 second
        this.getCommand("tmcd").setExecutor(this);
        getLogger().info("TimeClockPluginが有効になりました");
    } // 20 ticksごとに実行 (1秒 = 20 ticks)

    private void pauseWorking(String playerName) {
        if (isWorking.getOrDefault(playerName, false) && !pausedTimes.containsKey(playerName)) {
            pausedTimes.put(playerName, System.currentTimeMillis());
        }
    }

    @Override
    public void onDisable() {
        // プラグインが無効になる際に全てのプレイヤーの勤務時間を終了
        for (String playerName : startTimes.keySet()) {
            if (isWorking.getOrDefault(playerName, false)) {
                finishWorking(playerName);
            }
        }
        savePlayerData();
        getLogger().info("TimeClockPluginが無効になりました");
    }

    private void createPluginFolder() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
    }

    private void createPlayerDataFile() {
        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        if (!playerDataFile.exists()) {
            try {
                playerDataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadPlayerData() {
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void savePlayerData() {
        try {
            playerData.save(playerDataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }

        Player player = (Player) sender;
        String playerName = player.getName();

        if (command.getName().equalsIgnoreCase("tmcd")) {
            if (args.length == 0) {
                player.sendMessage(ChatColor.YELLOW + "Usage: /tmcd start|stop|restart|finish|check [MCID]");
                return true;
            }

            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "start":
                    startWorking(playerName);
                    player.sendMessage(ChatColor.GREEN + "勤務を開始しました。");
                    break;

                case "stop":
                    stopWorking(playerName);
                    player.sendMessage(ChatColor.YELLOW + "勤務を一時停止しました。");
                    break;

                case "restart":
                    restartWorking(playerName);
                    player.sendMessage(ChatColor.GREEN + "勤務を再開しました。");
                    break;

                case "finish":
                    finishWorking(playerName);
                    player.sendMessage(ChatColor.GREEN + "勤務を終了しました。");
                    break;

                case "check":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "MCIDを指定してください。");
                        return true;
                    }
                    String targetMCID = args[1];
                    checkPlayerWorkTime(player, targetMCID);
                    break;

                default:
                    player.sendMessage(ChatColor.RED + "無効なコマンドです。");
                    break;
            }
        }

        return true;
    }

    private void startWorking(String playerName) {
        startTimes.put(playerName, System.currentTimeMillis());
        pausedTimes.remove(playerName);
        isWorking.put(playerName, true);
    }

    private void stopWorking(String playerName) {
        if (isWorking.getOrDefault(playerName, false)) {
            pausedTimes.put(playerName, System.currentTimeMillis());
        }
    }

    private void restartWorking(String playerName) {
        if (isWorking.getOrDefault(playerName, false) && pausedTimes.containsKey(playerName)) {
            long pauseDuration = System.currentTimeMillis() - pausedTimes.get(playerName);
            startTimes.put(playerName, startTimes.get(playerName) + pauseDuration);
            pausedTimes.remove(playerName);
        }
    }

    private void finishWorking(String playerName) {
        if (isWorking.getOrDefault(playerName, false)) {
            long endTime = System.currentTimeMillis();
            long startTime = startTimes.get(playerName);
            long workDuration = (endTime - startTime) / 60000;  // Milliseconds to minutes

            if (pausedTimes.containsKey(playerName)) {
                workDuration -= ((endTime - pausedTimes.get(playerName)) / 60000);
                pausedTimes.remove(playerName);
            }

            long totalDuration = playerData.contains(playerName) ? playerData.getLong(playerName) + workDuration : workDuration;
            playerData.set(playerName, totalDuration);

            // Reset start and paused times, and disable working for this player
            startTimes.remove(playerName);
            pausedTimes.remove(playerName);
            isWorking.put(playerName, false);

            // このセッションの勤務時間をプレイヤーに通知
            Player player = Bukkit.getServer().getPlayer(playerName);
            if (player != null && player.isOnline()) {
                player.sendMessage("このセッションの勤務時間: " + workDuration + " 分");
            }
        }
    }

    private void checkPlayerWorkTime(Player sender, String targetMCID) {
        if (playerData.contains(targetMCID)) {
            long workTime = playerData.getLong(targetMCID);
            sender.sendMessage(ChatColor.GREEN + targetMCID + " の勤務時間: " + workTime + " 分");
        } else {
            sender.sendMessage(ChatColor.RED + "指定されたMCIDのデータは存在しません。");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        if (isWorking.getOrDefault(playerName, false)) {
            // プレイヤーがログアウトしたとき、勤務時間を終了
            finishWorking(playerName);
        }
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("tmcd")) {
            if (args.length == 1) {
                List<String> subCommands = new ArrayList<>();
                subCommands.add("start");
                subCommands.add("stop");
                subCommands.add("restart");
                subCommands.add("finish");
                subCommands.add("check");
                return subCommands;
            }
        }
        return null;
    }

}
