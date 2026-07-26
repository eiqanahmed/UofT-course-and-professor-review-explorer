package com.uoft.reviewexplorer.api;

import com.uoft.reviewexplorer.service.ReviewExplorerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExplorerController {
    private final ReviewExplorerService service;

    public ExplorerController(ReviewExplorerService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam Map<String, String> params) {
        return service.search(params);
    }

    @GetMapping("/courses/{courseCode}")
    public ResponseEntity<Map<String, Object>> course(@PathVariable String courseCode) {
        return service.courseDetail(courseCode)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/professors/{professorId}")
    public ResponseEntity<Map<String, Object>> professor(@PathVariable long professorId) {
        return service.professorDetail(professorId)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/reviews")
    public Map<String, Object> reviews(@RequestParam Map<String, String> params) {
        return Map.of("reviews", service.reviews(params));
    }
}
