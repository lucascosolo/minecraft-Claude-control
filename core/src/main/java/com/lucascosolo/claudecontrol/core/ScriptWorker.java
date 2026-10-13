package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.mozilla.javascript.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Trusted fixed child entry point. Scripts see only native JS values, never host objects. */
public final class ScriptWorker {
    static final int MAX_LINE=98304;
    private static final long MAX_INSTRUCTIONS=1_000_000, MAX_NANOS=250_000_000;
    private static final Gson JSON=new Gson();
    private ScriptWorker() {}
    private static final class LimitedFactory extends ContextFactory {
        private long started,instructions;
        @Override protected Context makeContext() {
            Context cx=super.makeContext(); cx.setOptimizationLevel(-1);cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(1000);cx.setClassShutter(name->false);
            cx.setWrapFactory(new WrapFactory() {
                @Override public Object wrap(Context c,Scriptable scope,Object value,Class<?> type) {
                    throw new IllegalStateException("Host values forbidden");
                }
                @Override public Scriptable wrapAsJavaObject(Context c,Scriptable scope,Object value,Class<?> type) {
                    throw new IllegalStateException("Host values forbidden");
                }
            });
            started=System.nanoTime();instructions=0;return cx;
        }
        @Override protected void observeInstructionCount(Context cx,int count) {
            instructions+=count;
            if(instructions>MAX_INSTRUCTIONS || System.nanoTime()-started>MAX_NANOS)throw new IllegalStateException("Script budget exceeded");
        }
    }
    private static final String BRIDGE="""
        (function(){
          const stringify=JSON.stringify, parse=JSON.parse, keys=Object.keys,
            proto=Object.getPrototypeOf, objectProto=Object.prototype, arrayProto=Array.prototype,
            isArray=Array.isArray, freeze=Object.freeze;
          function plain(x,d){
            if(d>32)throw Error('JSON depth exceeded');
            if(x===null || typeof x==='string' || typeof x==='boolean')return;
            if(typeof x==='number'){if(x!==x || x===Infinity || x===-Infinity)throw Error('Invalid number');return;}
            if(typeof x!=='object')throw Error('Only JSON values allowed');
            const p=proto(x);
            if(isArray(x)?p!==arrayProto:(p!==objectProto && p!==null))throw Error('Only plain JSON allowed');
            const k=keys(x);for(let i=0;i<k.length;i++)plain(x[k[i]],d+1);
          }
          return function(name,eventText,stateText,paramsText,validate){
            const event=parse(eventText),state=parse(stateText),params=parse(paramsText),actions=[];
            const fn=this[name];if(typeof fn!=='function')throw Error('Callback function missing');
            if(validate)return '{}';
            const game=freeze({emit:function(action){
              if(actions.length>=16)throw Error('Too many actions');
              plain(action,0);const text=stringify(action);
              if(text.length>4096)throw Error('Action too large');actions.push(parse(text));
            }});
            fn(event,state,game,params);
            plain(state,0);plain(actions,0);
            const result=stringify({state:state,actions:actions});
            if(result.length>65536)throw Error('Output too large');return result;
          };
        })()
        """;

    static JsonObject execute(JsonObject request) {
        return new LimitedFactory().call(cx->{
            ScriptableObject scope=cx.initSafeStandardObjects();
            // Defense in depth: no engine-provided Java package or host entry points.
            for(String name:new String[]{"Packages","java","javax","org","com","edu","net","Java","JavaAdapter","JavaImporter","importClass","importPackage","getClass","load","readFile","readUrl","print","console","quit"})
                ScriptableObject.deleteProperty(scope,name);
            Function bridge=(Function)cx.evaluateString(scope,BRIDGE,"bridge",1,null);
            String script=ExperienceSpec.text(request,"script",16384,true);
            cx.evaluateString(scope,script,"experience",1,null);
            JsonObject result;
            if(request.get("op").getAsString().equals("validate")) {
                for(var fn:request.getAsJsonArray("functions"))bridge.call(cx,scope,scope,new Object[]{fn.getAsString(),"{}","{}","{}",true});
                result=new JsonObject();
            } else {
                Object output=bridge.call(cx,scope,scope,new Object[]{request.get("function").getAsString(),
                    request.get("event").toString(),request.get("state").toString(),request.get("params").toString(),false});
                result=JsonParser.parseString(Context.toString(output)).getAsJsonObject();
            }
            result.addProperty("ok",true);return result;
        });
    }
    public static void main(String[] args) throws IOException {
        try(var input=new InputStreamReader(System.in,StandardCharsets.UTF_8);
            var output=new BufferedWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8))) {
            String line;
            while((line=readLine(input))!=null) {
                JsonObject result;
                try { result=execute(JsonParser.parseString(line).getAsJsonObject()); }
                catch(Throwable failure) {
                    // Never return engine stack traces, paths, or environment details.
                    result=new JsonObject();result.addProperty("ok",false);result.addProperty("error","Script rejected or budget exceeded");
                }
                String serialized=JSON.toJson(result);
                if(serialized.length()>MAX_LINE)serialized="{\"ok\":false,\"error\":\"Output limit exceeded\"}";
                output.write(serialized);output.newLine();output.flush();
            }
        }
    }
    static String readLine(Reader input) throws IOException {
        StringBuilder result=new StringBuilder();int ch;
        while((ch=input.read())!=-1) {
            if(ch=='\n')return result.toString();
            if(result.length()>=MAX_LINE)throw new IOException("Protocol limit exceeded");
            result.append((char)ch);
        }
        return result.isEmpty()?null:result.toString();
    }
}
