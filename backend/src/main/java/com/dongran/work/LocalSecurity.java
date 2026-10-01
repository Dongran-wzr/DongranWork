package com.dongran.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalSecurity extends OncePerRequestFilter {
    private final String token;
    private final Path dataDirectory;
    private final ObjectMapper mapper;
    public LocalSecurity(@Value("${dongran.token:}") String configured,Path dataDirectory,ObjectMapper mapper) {
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        this.token=configured.isBlank()?Base64.getUrlEncoder().withoutPadding().encodeToString(bytes):configured;
        if(token.length()<32)throw new IllegalArgumentException("DONGRAN_TOKEN must have at least 32 characters");
        this.dataDirectory=dataDirectory;this.mapper=mapper;
    }
    @EventListener
    void ready(WebServerInitializedEvent event) throws IOException {
        Path ready=dataDirectory.resolve("runtime.json");
        Files.writeString(ready,mapper.writeValueAsString(Map.of("port",event.getWebServer().getPort(),"token",token,"pid",ProcessHandle.current().pid())),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        if(Files.getFileStore(ready).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(ready,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        logger.info("Dongran local service ready on 127.0.0.1:"+event.getWebServer().getPort());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Referrer-Policy","no-referrer");
        if(!request.getRequestURI().startsWith("/api/")||request.getRequestURI().equals("/api/health")||request.getRequestURI().equals("/api/session")) {chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");
        String origin=request.getHeader("Origin");
        if(origin!=null) {
            try {
                URI uri=URI.create(origin);
                if(!Set.of("127.0.0.1","localhost").contains(uri.getHost())||uri.getPort()!=request.getLocalPort()) { reject(response,403,"来源不受信任。");return; }
            } catch(Exception e) {reject(response,403,"来源不受信任。");return;}
        }
        String supplied=request.getHeader("Authorization");
        supplied=supplied!=null&&supplied.startsWith("Bearer ")?supplied.substring(7):"";
        if(supplied.isEmpty()&&request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(cookie.getName().equals("dongran_session"))supplied=cookie.getValue();
        if(!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {reject(response,401,"需要连接本机工作台。");return;}
        if(!Set.of("GET","HEAD").contains(request.getMethod())&&!"desktop".equals(request.getHeader("X-Dongran-Client"))) {reject(response,403,"请求缺少客户端标识。");return;}
        chain.doFilter(request,response);
    }
    void session(HttpServletResponse response) { response.addHeader("Set-Cookie","dongran_session="+token+"; Path=/api; HttpOnly; SameSite=Strict"); }
    private void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(),Map.of("code",status==401?"UNAUTHORIZED":"FORBIDDEN","message",message));
    }
}
