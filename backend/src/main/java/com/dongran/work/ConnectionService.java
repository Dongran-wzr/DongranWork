package com.dongran.work;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class ConnectionService {
    private final Database db;private final PreferenceService preferences;private final CredentialService credentials;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ConnectionService(Database db,PreferenceService preferences,CredentialService credentials){this.db=db;this.preferences=preferences;this.credentials=credentials;}
    private Map<String,Object> connection(String id){var item=preferences.collection("serviceConnections").stream().filter(c->id.equals(c.get("id"))).findFirst().orElseThrow(()->ApiException.missing("连接不存在。"));if(!Database.bool(item,"enabled",true))throw ApiException.conflict("连接已禁用。");return item;}
    public Object test(String id)throws Exception{
        var connection=connection(id);URI uri=PreferenceService.validateUrl(Database.required(connection,"url",2048));
        if("github".equals(connection.get("type"))){
            URI endpoint=uri.getHost().equals("github.com")?URI.create("https://api.github.com/user"):URI.create(uri.toString().replaceAll("/+$","")+"/user");
            var response=client.send(request(endpoint,id).header("Accept","application/vnd.github+json").GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw ApiException.bad("GitHub 连接失败，HTTP "+response.statusCode());
            var result=db.mapper.readTree(response.body());return Map.of("connected",true,"login",result.path("login").asText());
        }
        return Map.of("connected",true,"tools",tools(id));
    }
    public Object tools(String id)throws Exception{try(var session=new Session(connection(id),id)){session.initialize();return session.rpc("tools/list",Map.of()).path("tools");}}
    public Object call(String id,String name,Map<String,Object> arguments)throws Exception{try(var session=new Session(connection(id),id)){session.initialize();return session.rpc("tools/call",Map.of("name",name,"arguments",arguments));}}
    private HttpRequest.Builder request(URI uri,String id){var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25));String token=credentials.get(id);if(!token.isBlank())builder.header("Authorization","Bearer "+token);return builder;}
    private class Session implements AutoCloseable{
        final URI original;URI endpoint;final String id;String sessionId;int sequence=1;InputStream legacy;BufferedReader reader;
        final ScheduledExecutorService deadline=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
        Session(Map<String,Object> connection,String id)throws Exception{
            this.id=id;original=PreferenceService.validateUrl(Database.required(connection,"url",2048));endpoint=original;
            if("mcp-sse".equals(connection.get("type"))){
                var response=client.send(request(original,id).header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                if(response.statusCode()!=200){response.body().close();throw ApiException.bad("MCP SSE 连接失败。");}
                legacy=response.body();deadline.schedule(()->{try{legacy.close();}catch(IOException ignored){}},30,TimeUnit.SECONDS);reader=new BufferedReader(new InputStreamReader(legacy,StandardCharsets.UTF_8));
                String line;boolean endpointEvent=false;
                while((line=reader.readLine())!=null){if(line.equals("event: endpoint"))endpointEvent=true;else if(endpointEvent&&line.startsWith("data:")){endpoint=original.resolve(line.substring(5).trim());break;}}
                if(endpoint.equals(original)||!Objects.equals(endpoint.getScheme(),original.getScheme())||!Objects.equals(endpoint.getHost(),original.getHost())||endpoint.getPort()!=original.getPort())throw ApiException.forbidden("MCP 返回的消息地址必须属于同一服务。");
            }
        }
        void initialize()throws Exception{rpc("initialize",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","Dongran Work","version","0.1.0")));send(Map.of("jsonrpc","2.0","method","notifications/initialized"),0);}
        JsonNode rpc(String method,Map<String,Object> params)throws Exception{return send(Map.of("jsonrpc","2.0","id",sequence,"method",method,"params",params),sequence++);}
        JsonNode send(Map<String,Object> payload,int expected)throws Exception{
            var builder=request(endpoint,id).header("Content-Type","application/json").header("Accept","application/json, text/event-stream");
            if(sessionId!=null)builder.header("Mcp-Session-Id",sessionId).header("MCP-Protocol-Version","2025-03-26");
            var response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString(db.json(payload))).build(),HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()<200||response.statusCode()>=300){response.body().close();throw ApiException.bad("MCP 服务返回 HTTP "+response.statusCode());}
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value->sessionId=value);
            JsonNode node;
            try(var input=response.body()){
                if(expected==0)return db.mapper.createObjectNode();
                if(legacy!=null){input.close();node=readSse(reader,expected);}
                else if(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")){
                    var timer=deadline.schedule(()->{try{input.close();}catch(IOException ignored){}},25,TimeUnit.SECONDS);
                    try{node=readSse(new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8)),expected);}finally{timer.cancel(false);}
                }else{byte[] bytes=input.readNBytes(1_000_001);if(bytes.length>1_000_000)throw ApiException.bad("MCP 响应过大。");node=db.mapper.readTree(bytes);}
            }
            if(node==null||node.path("id").asInt(-1)!=expected)throw ApiException.bad("MCP 响应标识不匹配。");
            if(node.has("error"))throw ApiException.bad("MCP 调用失败："+node.path("error").path("message").asText("未知错误"));return node.path("result");
        }
        JsonNode readSse(BufferedReader source,int expected)throws Exception{String line;int total=0;while((line=source.readLine())!=null){total+=line.length();if(total>1_000_000)throw ApiException.bad("MCP 响应过大。");if(line.startsWith("data:")){JsonNode node=db.mapper.readTree(line.substring(5).trim());if(node.path("id").asInt(-1)==expected)return node;}}throw ApiException.bad("MCP 连接提前结束。");}
        public void close(){deadline.shutdownNow();try{if(legacy!=null)legacy.close();}catch(IOException ignored){}}
    }
}
