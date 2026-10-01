package com.dongran.work;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class ModelClient {
    private final Database db;private final PreferenceService preferences;private final CredentialService credentials;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService timeouts=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("model-timeout").factory());
    public record Result(String text,List<Map<String,Object>> calls,Map<String,Object> message){}
    public ModelClient(Database db,PreferenceService preferences,CredentialService credentials){this.db=db;this.preferences=preferences;this.credentials=credentials;}
    public Map<String,Object> status(){return Map.of("configured",!preferences.string("modelName","").isBlank(),"model",preferences.string("modelName",""),"baseUrl",preferences.string("baseUrl","http://localhost:11434/v1"),"credentialConfigured",!credentials.get("model").isBlank());}
    public Result complete(List<Map<String,Object>> messages,List<Map<String,Object>> tools,Consumer<String> delta) throws Exception{
        String model=preferences.string("modelName","");if(model.isBlank())throw ApiException.bad("请先在模型设置中填写服务地址和模型名称。");
        URI base=PreferenceService.validateUrl(preferences.string("baseUrl","http://localhost:11434/v1"));
        String key=credentials.get("model");boolean local=Set.of("localhost","127.0.0.1","::1","[::1]").contains(base.getHost());
        if(!local&&key.isBlank())throw ApiException.bad("请先配置模型 API 密钥。");
        var payload=new LinkedHashMap<String,Object>();payload.put("model",model);payload.put("messages",messages);payload.put("stream",true);
        if(!tools.isEmpty()){payload.put("tools",tools);payload.put("tool_choice","auto");payload.put("parallel_tool_calls",false);}
        String serialized=db.json(payload);
        int limit=Database.number(preferences.all(),"contextWindow",32768,4096,131072)*3;
        if(serialized.length()>limit)throw ApiException.bad("任务上下文已超过配置上限，请拆分任务或提高上限。");
        var request=HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/+$","")+"/chat/completions")).timeout(Duration.ofSeconds(120)).header("Content-Type","application/json").header("Accept","text/event-stream");
        if(!key.isBlank())request.header("Authorization","Bearer "+key);
        var response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(serialized)).build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var input=response.body()){
            if(response.statusCode()<200||response.statusCode()>=300)throw ApiException.bad("模型服务返回 HTTP "+response.statusCode()+"，请检查地址、模型名称、密钥和额度。");
            var timer=timeouts.schedule(()->{try{input.close();}catch(IOException ignored){}},180,TimeUnit.SECONDS);
            try{
                StringBuilder text=new StringBuilder();var calls=new TreeMap<Integer,Map<String,Object>>();
                if(!response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")){
                    byte[] bytes=input.readNBytes(2_000_001);if(bytes.length>2_000_000)throw ApiException.bad("模型响应超过限制。");
                    JsonNode message=db.mapper.readTree(bytes).path("choices").path(0).path("message");
                    String content=message.path("content").asText("");delta.accept(content);
                    List<Map<String,Object>> result=new ArrayList<>();for(JsonNode tool:message.path("tool_calls"))result.add(db.object(tool.toString()));
                    return result(content,result);
                }
                try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
                    String line;int total=0;boolean finished=false;
                    while((line=reader.readLine())!=null){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                        total+=line.length();if(total>4_000_000||line.length()>1_000_000)throw ApiException.bad("模型响应超过限制。");
                        if(!line.startsWith("data:"))continue;
                        String value=line.substring(5).trim();if(value.equals("[DONE]")){finished=true;break;}if(value.isBlank())continue;
                        JsonNode envelope=db.mapper.readTree(value);if(envelope.has("error"))throw ApiException.bad("模型服务返回流式错误。");
                        JsonNode choice=envelope.path("choices").path(0),part=choice.path("delta");
                        if(!choice.path("finish_reason").isNull()&&!choice.path("finish_reason").isMissingNode())finished=true;
                        if(part.path("content").isTextual()){String fragment=part.path("content").asText();text.append(fragment);delta.accept(fragment);}
                        for(JsonNode call:part.path("tool_calls")){
                            int index=call.path("index").asInt();if(index<0||index>32)throw ApiException.bad("模型工具调用数量超过限制。");
                            var target=calls.computeIfAbsent(index,n->new LinkedHashMap<>(Map.of("id","","name","","arguments","")));
                            if(call.has("id"))target.put("id",call.path("id").asText());
                            if(call.path("function").has("name"))target.put("name",call.path("function").path("name").asText());
                            if(call.path("function").has("arguments"))target.put("arguments",String.valueOf(target.get("arguments"))+call.path("function").path("arguments").asText());
                        }
                    }
                    if(!finished)throw ApiException.bad("模型响应意外中断，请重试。");
                }
                var completeCalls=new ArrayList<Map<String,Object>>();
                for(var call:calls.values()){
                    if(String.valueOf(call.get("id")).isBlank()||String.valueOf(call.get("name")).isBlank())throw ApiException.bad("模型返回不完整的工具调用。");
                    completeCalls.add(Map.of("id",call.get("id"),"type","function","function",Map.of("name",call.get("name"),"arguments",call.get("arguments"))));
                }
                return result(text.toString(),completeCalls);
            }finally{timer.cancel(false);}
        }
    }
    private Result result(String text,List<Map<String,Object>> calls){var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content",text);if(!calls.isEmpty())message.put("tool_calls",calls);return new Result(text,calls,message);}
    @PreDestroy void close(){timeouts.shutdownNow();client.close();}
}
