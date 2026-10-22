package com.hdsl.server.api;

import com.hdsl.server.job.JobManager;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** 任务状态、SSE 事件与取消（方案 §4.2/§4.3）。 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobManager jobs;

    public JobController(JobManager jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public List<JobManager.View> list() {
        return jobs.recent();
    }

    @GetMapping("/{id}")
    public JobManager.View view(@PathVariable("id") String id) {
        return jobs.view(id);
    }

    /** SSE：state / log / result / error，15 秒心跳；断线可用 Last-Event-ID 续传。 */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long last = 0L;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                last = Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ignored) {
                last = 0L;
            }
        }
        return jobs.subscribe(id, last);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobManager.View> cancel(@PathVariable("id") String id) {
        return ResponseEntity.accepted().body(jobs.cancel(id));
    }
}
