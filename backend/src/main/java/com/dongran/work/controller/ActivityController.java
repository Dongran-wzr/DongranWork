package com.dongran.work.controller;

import com.dongran.work.repository.ActivityRepository;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ActivityController {
  private final ActivityRepository repository;

  public ActivityController(ActivityRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/activity")
  public Object activity(@RequestParam(required = false) String projectId) {
    return repository.daily(projectId);
  }
}
