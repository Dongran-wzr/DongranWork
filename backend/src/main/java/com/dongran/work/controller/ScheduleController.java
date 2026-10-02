package com.dongran.work.controller;

import com.dongran.work.dto.ScheduleWriteRequest;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.service.ScheduleService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ScheduleController {
  private final ScheduleService schedules;
  private final Database db;

  public ScheduleController(ScheduleService schedules, Database db) {
    this.schedules = schedules;
    this.db = db;
  }

  @GetMapping("/schedules")
  public Object schedules() {
    return schedules.snapshot();
  }

  @PutMapping("/schedules")
  public Object replaceSchedules(@RequestBody Map<String, Object> body) {
    return schedules.replace(body);
  }

  @PostMapping("/schedules")
  public Object addSchedule(@Valid @RequestBody ScheduleWriteRequest body) {
    return schedules.save(null, db.object(db.json(body)));
  }

  @PutMapping("/schedules/{id}")
  public Object editSchedule(
      @PathVariable String id, @Valid @RequestBody ScheduleWriteRequest body) {
    return schedules.save(id, db.object(db.json(body)));
  }

  @DeleteMapping("/schedules/{id}")
  public Object deleteSchedule(@PathVariable String id) {
    schedules.delete(id);
    return Map.of("deleted", true);
  }

  @PostMapping("/schedules/{id}/run")
  public Object runSchedule(@PathVariable String id) {
    return schedules.runNow(id);
  }

  @GetMapping("/schedules/{id}/runs")
  public Object scheduleRuns(@PathVariable String id) {
    return schedules.runs(id);
  }
}
