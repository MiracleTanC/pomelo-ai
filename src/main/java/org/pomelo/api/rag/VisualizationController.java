package org.pomelo.api.rag;

import lombok.RequiredArgsConstructor;
import org.pomelo.common.api.Result;
import org.pomelo.core.rag.VectorVisualizer;
import org.springframework.web.bind.annotation.*;

/**
 * 向量可视化接口：返回 2D PCA 降维散点数据。
 */
@RestController
@RequestMapping("/api/viz")
@RequiredArgsConstructor
public class VisualizationController {

    private final VectorVisualizer visualizer;

    @GetMapping("/scatter/{kbId}")
    public Result<VectorVisualizer.VisualizationResult> scatter(@PathVariable String kbId) {
        return Result.ok(visualizer.visualize(kbId));
    }
}
