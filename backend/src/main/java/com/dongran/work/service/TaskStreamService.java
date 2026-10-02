package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TaskStreamService {
  private final AgentService agents;
  private final TaskEvents events;
  private final Semaphore streamSlots = new Semaphore(32);

  public TaskStreamService(AgentService agents, TaskEvents events) {
    this.agents = agents;
    this.events = events;
  }

  public SseEmitter stream(String id, long after, String last) {
    agents.get(id);
    if (!streamSlots.tryAcquire()) throw ApiException.conflict("事件连接数过多。");
    long cursor = after;
    if (last != null)
      try {
        cursor = Math.max(cursor, Long.parseLong(last));
      } catch (NumberFormatException ignored) {
      }
    SseEmitter emitter = new SseEmitter(1_800_000L);
    long initial = cursor;
    var open = new java.util.concurrent.atomic.AtomicBoolean(true);
    emitter.onCompletion(() -> open.set(false));
    emitter.onTimeout(() -> open.set(false));
    emitter.onError(error -> open.set(false));
    Thread.startVirtualThread(
        () -> {
          long current = initial, lastPing = 0;
          try {
            while (open.get()) {
              var entries = events.since(id, current);
              for (var entry : entries) {
                current = ((Number) entry.get("id")).longValue();
                emitter.send(
                    SseEmitter.event()
                        .id(String.valueOf(current))
                        .name(String.valueOf(entry.get("type")))
                        .data(entry.get("data")));
              }
              String status = String.valueOf(agents.get(id).get("status"));
              if (entries.isEmpty()
                  && !Set.of("queued", "running", "awaiting_approval").contains(status)) {
                emitter.send(SseEmitter.event().name("end").data(Map.of("status", status)));
                break;
              }
              if (System.currentTimeMillis() - lastPing > 10000) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
                lastPing = System.currentTimeMillis();
              }
              Thread.sleep(150);
            }
            emitter.complete();
          } catch (Exception e) {
            emitter.completeWithError(e);
          } finally {
            streamSlots.release();
          }
        });
    return emitter;
  }
}
