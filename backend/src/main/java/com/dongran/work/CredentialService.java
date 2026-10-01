package com.dongran.work;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class CredentialService {
    private final Path directory;
    private final ConcurrentMap<String,String> session=new ConcurrentHashMap<>();
    public CredentialService(Path dataDirectory){directory=dataDirectory.resolve("credentials");}
    private String os(){return System.getProperty("os.name").toLowerCase(Locale.ROOT);}
    private void id(String id){if(!id.matches("[a-zA-Z0-9_-]{1,100}"))throw ApiException.bad("凭据标识不合法。");}
    public Map<String,Object> save(String id,String value,boolean remember){
        id(id);if(value.length()>16000||value.contains("\n")||value.contains("\r"))throw ApiException.bad("凭据格式不合法。");
        if(value.isBlank()){delete(id);return Map.of("configured",false,"storage","none");}
        session.put(id,value);String storage="session";
        if(remember){try{
            if(os().contains("win")){Files.createDirectories(directory);Files.write(directory.resolve(id+".bin"),Crypt32Util.cryptProtectData(value.getBytes(StandardCharsets.UTF_8)));storage="windows-dpapi";}
            else if(os().contains("mac")){platform(List.of("security","add-generic-password","-U","-a",id,"-s","com.dongran.work","-w",value),null);storage="macos-keychain";}
            else{platform(List.of("secret-tool","store","--label=Dongran Work","service","com.dongran.work","account",id),value);storage="linux-secret-service";}
        }catch(Exception ignored){storage="session";}}
        return Map.of("configured",true,"storage",storage,"persistent",!storage.equals("session"));
    }
    public String get(String id){
        id(id);if(session.containsKey(id))return session.get(id);
        if(id.equals("model")&&System.getenv("DONGRAN_MODEL_API_KEY")!=null)return System.getenv("DONGRAN_MODEL_API_KEY");
        try{
            String value;
            if(os().contains("win")){Path file=directory.resolve(id+".bin");if(!Files.exists(file))return "";value=new String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)),StandardCharsets.UTF_8);}
            else if(os().contains("mac"))value=platform(List.of("security","find-generic-password","-a",id,"-s","com.dongran.work","-w"),null).strip();
            else value=platform(List.of("secret-tool","lookup","service","com.dongran.work","account",id),null).strip();
            if(!value.isBlank())session.put(id,value);return value;
        }catch(Exception e){return "";}
    }
    public void delete(String id){
        id(id);session.remove(id);
        try{
            if(os().contains("win"))Files.deleteIfExists(directory.resolve(id+".bin"));
            else if(os().contains("mac"))platform(List.of("security","delete-generic-password","-a",id,"-s","com.dongran.work"),null);
            else platform(List.of("secret-tool","clear","service","com.dongran.work","account",id),null);
        }catch(Exception e){throw ApiException.conflict("系统凭据删除失败，原凭据可能仍存在。");}
    }
    private String platform(List<String> arguments,String stdin)throws Exception{
        Process process=new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{
            try(var input=process.getOutputStream()){if(stdin!=null)input.write(stdin.getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(8,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Credential store unavailable");
            return new String(process.getInputStream().readNBytes(20000),StandardCharsets.UTF_8);
        }finally{if(process.isAlive())CommandService.terminate(process);}
    }
}
