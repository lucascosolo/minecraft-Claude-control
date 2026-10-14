package com.lucascosolo.claudecontrol.core;

import com.lucascosolo.claudecontrol.core.bootstrap.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stable Bukkit host. Reloads only ClaudeControl's private runtime, never other plugins. */
public final class ClaudeControlPlugin extends JavaPlugin implements CommandExecutor {
    private record Loaded(Path jar,RuntimeClassLoader loader,RuntimeLifecycle runtime,String sha256){}
    private volatile Loaded active;
    private volatile Loaded staged;
    private Path baseline;
    private volatile String outcome="ready";
    private volatile boolean disabled;
    private final AtomicBoolean reloading=new AtomicBoolean();
    private final ExecutorService lifecycle=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ClaudeControl-runtime-loader");t.setDaemon(true);return t;});
    @Override public void onEnable(){
        try {
            baseline=RuntimeArchive.snapshot(getDataFolder().toPath().getParent().resolve("ClaudeControl.jar"),archive());
            active=load(baseline);active.runtime().start(false);
            Objects.requireNonNull(getCommand("claude")).setExecutor(this);
        }catch(Exception|LinkageError failure){
            getLogger().severe("ClaudeControl runtime initialization refused; existing world and private data preserved.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    private Path archive(){return getDataFolder().toPath().resolve("runtime-archive");}
    private Loaded load(Path jar) throws Exception {
        RuntimeClassLoader loader=new RuntimeClassLoader(jar.toUri().toURL(),getClass().getClassLoader());
        try {
            RuntimeLifecycle runtime=(RuntimeLifecycle)loader.loadClass("com.lucascosolo.claudecontrol.core.ClaudeRuntime").getConstructor(JavaPlugin.class).newInstance(this);
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(jar)));
            return new Loaded(jar,loader,runtime,digest);
        }catch(Exception|LinkageError failure){loader.close();throw failure;}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        Loaded current=active;
        if(args.length>0 && args[0].equalsIgnoreCase("runtime-status")){
            if(!(sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) && (current==null || !current.runtime().owner(sender))){sender.sendMessage("Runtime status requires an owner or server console.");return true;}
            sender.sendMessage("Runtime: "+outcome+"; sha256 "+(current==null?"none":current.sha256()));return true;
        }
        if(args.length>0 && args[0].equalsIgnoreCase("runtime-reload")){
            if(current==null || !current.runtime().owner(sender)){sender.sendMessage("Runtime reload requires the configured owner, an operator, or the server console.");return true;}
            if(!reloading.compareAndSet(false,true)){sender.sendMessage("A runtime reload is already in progress.");return true;}
            outcome="validating";
            sender.sendMessage("Validating installed ClaudeControl runtime; server and player sessions continue.");
            lifecycle.execute(()->stage(sender,current));return true;
        }
        if(current==null || reloading.get()){sender.sendMessage("ClaudeControl runtime is switching; retry shortly.");return true;}
        return current.runtime().onCommand(sender,command,label,args);
    }
    private void stage(CommandSender sender,Loaded previous){
        try {
            // No path is accepted from commands. Only the installed, owned plugin artifact.
            Path installed=getDataFolder().toPath().getParent().resolve("ClaudeControl.jar");
            Path copy=RuntimeArchive.snapshot(installed,archive());RuntimeArchive.compatible(baseline,copy);
            Loaded candidate=load(copy);staged=candidate;
            schedule(()->{
                if(active!=previous || !previous.runtime().owner(sender)){close(candidate);staged=null;reloading.set(false);outcome="refused";sender.sendMessage("Runtime reload authorization changed; previous runtime retained.");return;}
                boolean paused=previous.runtime().paused();
                stop(previous);active=null;
                lifecycle.execute(()->switchAfterStop(sender,previous,candidate,paused));
            });
        }catch(Exception|LinkageError failure){
            schedule(()->{reloading.set(false);outcome="refused";sender.sendMessage("Runtime reload refused; installed jar invalid or bootstrap changed. Previous runtime retained; bootstrap updates require a server restart.");});
        }
    }
    private void switchAfterStop(CommandSender sender,Loaded previous,Loaded candidate,boolean paused){
        try {
            if(!previous.runtime().awaitStopped(10_000)){
                close(candidate);staged=null;outcome="halted";
                schedule(()->sender.sendMessage("Runtime reload stopped safely: an old worker has not terminated. Previous bytes retained; restart before retrying."));return;
            }
            close(previous);schedule(()->activate(sender,previous,candidate,paused));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();close(candidate);staged=null;}
    }
    private void activate(CommandSender sender,Loaded previous,Loaded candidate,boolean paused){
        if(disabled){close(candidate);return;}
        try {
            candidate.runtime().start(paused);active=candidate;staged=null;reloading.set(false);outcome="reloaded";
            sender.sendMessage("ClaudeControl runtime reloaded; world, players, memory and companion identity preserved.");
        }catch(Exception|LinkageError failure){
            stop(candidate);
            lifecycle.execute(()->{
                try {
                    if(!candidate.runtime().awaitStopped(10_000))throw new IllegalStateException("Candidate worker remained active");
                    close(candidate);staged=null;Loaded rollback=load(previous.jar());staged=rollback;
                    schedule(()->{
                        try{rollback.runtime().start(paused);active=rollback;staged=null;reloading.set(false);outcome="rolled back";sender.sendMessage("Updated runtime failed; previous archived runtime restored.");}
                        catch(Exception|LinkageError refused){stop(rollback);outcome="halted";sender.sendMessage("Runtime rollback refused; plugin stopped safely. Archived bytes and all private data retained.");}
                    });
                }catch(Exception|LinkageError refused){outcome="halted";schedule(()->sender.sendMessage("Runtime rollback refused; plugin stopped safely. Archived bytes and all private data retained."));}
            });
        }
    }
    private void schedule(Runnable action){
        if(disabled){Loaded pending=staged;if(pending!=null)close(pending);return;}
        try{Bukkit.getScheduler().runTask(this,()->{if(!disabled)action.run();});}
        catch(IllegalStateException refused){Loaded pending=staged;if(pending!=null)close(pending);}
    }
    private void stop(Loaded loaded){
        try{loaded.runtime().stop();}
        finally{HandlerList.unregisterAll(this);Bukkit.getScheduler().cancelTasks(this);}
    }
    private void close(Loaded loaded){try{loaded.loader().close();}catch(Exception ignored){getLogger().warning("A runtime archive handle could not close.");}}
    @Override public void onDisable(){
        disabled=true;Loaded previous=active;active=null;
        if(previous!=null){stop(previous);close(previous);}
        Loaded pending=staged;staged=null;if(pending!=null){stop(pending);close(pending);}
        lifecycle.shutdownNow();
    }
}
