package com.dongran.work.controller;

import com.dongran.work.exception.ApiException;
import com.dongran.work.service.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/web-search")
public class WebSearchController {
  private final SearchProviderService settings;
  private final WebToolsService web;
  private final PreferenceService preferences;

  public WebSearchController(
      SearchProviderService settings, WebToolsService web, PreferenceService preferences) {
    this.settings = settings;
    this.web = web;
    this.preferences = preferences;
  }

  @GetMapping
  public Object get() {
    return settings.view();
  }

  @PutMapping
  public Object save(@RequestBody Map<String, Object> body) {
    return settings.save(body);
  }

  @PostMapping("/test")
  public Object test() {
    if (!preferences.bool("allowNetwork", false))
      throw ApiException.forbidden("请先在权限与安全中允许工具网络访问。");
    return web.search("Java 21 virtual threads documentation");
  }
}
