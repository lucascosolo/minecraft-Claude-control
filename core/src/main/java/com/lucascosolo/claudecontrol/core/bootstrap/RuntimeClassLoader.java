package com.lucascosolo.claudecontrol.core.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;

/** Only this plugin's owned implementation is child-first; platform classes stay shared. */
public final class RuntimeClassLoader extends URLClassLoader {
    private static final String OWNED="com.lucascosolo.claudecontrol.core.";
    public RuntimeClassLoader(URL jar,ClassLoader parent){super(new URL[]{jar},parent);}
    public static boolean runtimeClass(String name){
        return name.startsWith(OWNED) && !name.startsWith(OWNED+"bootstrap.")
            && !name.equals(OWNED+"ClaudeControlPlugin") && !name.startsWith(OWNED+"ClaudeControlPlugin$");
    }
    @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
        synchronized(getClassLoadingLock(name)) {
            Class<?> type=findLoadedClass(name);
            if(type==null)type=runtimeClass(name)?findClass(name):super.loadClass(name,false);
            if(resolve)resolveClass(type);
            return type;
        }
    }
    @Override public URL getResource(String name){URL own=findResource(name);return own==null?super.getResource(name):own;}
}
