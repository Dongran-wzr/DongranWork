package com.dongran.work.controller;

import com.dongran.work.infrastructure.ModelClient;
import com.dongran.work.security.LocalSecurity;
import com.dongran.work.service.PreferenceService;
import com.dongran.work.service.ProjectService;
import com.dongran.work.service.ScheduleService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SystemController {
  private final LocalSecurity security;
  private final PreferenceService preferences;
  private final ProjectService projects;
  private final ScheduleService schedules;
  private final ModelClient model;

  public SystemController(
      LocalSecurity security,
      PreferenceService preferences,
      ProjectService projects,
      ScheduleService schedules,
      ModelClient model) {
    this.security = security;
    this.preferences = preferences;
    this.projects = projects;
    this.schedules = schedules;
    this.model = model;
  }

  @GetMapping("/health")
  public Object health() {
    return Map.of("status", "ok", "version", "0.1.0");
  }

  @PostMapping("/session")
  public Object session(HttpServletResponse response) {
    security.session(response);
    return Map.of("connected", true);
  }

  @GetMapping("/bootstrap")
  public Object bootstrap() {
    return Map.of(
        "settings",
        preferences.all(),
        "projects",
        projects.list(),
        "schedules",
        schedules.snapshot(),
        "model",
        model.status(),
        "timezone",
        java.time.ZoneId.systemDefault().toString());
  }
}
