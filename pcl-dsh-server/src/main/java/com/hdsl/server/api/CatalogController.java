package com.hdsl.server.api;

import com.hdsl.server.catalog.CatalogApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区插件目录（方案 §4.2，M3 UI 预留）：
 * <ul>
 *   <li>GET /api/plugin-catalog?q=&amp;page=&amp;refresh= → 搜索页（entries/total/page/hasNext/fromCache/warning）</li>
 *   <li>GET /api/plugin-catalog/{owner}/{repo}?refresh= → 项目解析（status/message/fromCache/targets）</li>
 * </ul>
 * 仅公开元数据；网络失败返回中文 502/504。
 */
@RestController
@RequestMapping("/api/plugin-catalog")
public class CatalogController {

    private final CatalogApiService catalog;

    public CatalogController(CatalogApiService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ApiDtos.PluginCatalogDto search(
            @RequestParam(value = "q", required = false, defaultValue = "") String query,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        return catalog.search(query, page, refresh);
    }

    @GetMapping("/{owner}/{repo}")
    public ApiDtos.PluginDetailsDto details(@PathVariable("owner") String owner,
                                            @PathVariable("repo") String repo,
                                            @RequestParam(value = "refresh", required = false, defaultValue = "false")
                                            boolean refresh) {
        return catalog.resolve(owner, repo, refresh);
    }
}
