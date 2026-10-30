package org.pomelo.api.kb;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.pomelo.api.kb.req.KbCreateRequest;
import org.pomelo.api.kb.req.KbUpdateRequest;
import org.pomelo.api.kb.vo.KbVo;
import org.pomelo.common.api.Result;
import org.pomelo.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;

    @PostMapping
    public Result<KbVo> create(@Valid @RequestBody KbCreateRequest req) {
        return Result.ok(kbService.create(req));
    }

    @GetMapping
    public Result<List<KbVo>> list() {
        return Result.ok(kbService.list());
    }

    @GetMapping("/{id}")
    public Result<KbVo> get(@PathVariable String id) {
        return Result.ok(kbService.get(id));
    }

    @PutMapping("/{id}")
    public Result<KbVo> update(@PathVariable String id, @RequestBody KbUpdateRequest req) {
        return Result.ok(kbService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        kbService.delete(id);
        return Result.ok();
    }
}
