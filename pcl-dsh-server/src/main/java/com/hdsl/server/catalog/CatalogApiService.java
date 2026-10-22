package com.hdsl.server.catalog;

import com.hdsl.download.PluginCatalogService;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.CatalogProvider;
import com.hdsl.server.log.LogFiles;
import com.hdsl.server.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 社区插件目录接口业务（方案 §4.2，M3 UI 预留）：搜索与项目解析。
 *
 * <p>复用 pcl-dsh-core 的 {@link PluginCatalogService}（仅 GitHub/npm 公开元数据，
 * 不读取 GitHub 登录凭据、不下载或执行插件代码）；代理来自 settings.proxy。
 * 发现类请求不占用全局变更锁，可与其他任务并行。</p>
 */
@Component
public class CatalogApiService {

    private static final Logger log = LoggerFactory.getLogger(CatalogApiService.class);
    private static final String GENERIC_FAILURE = "插件目录查询失败，请检查网络或代理后重试。";

    private final CatalogProvider catalogs;
    private final LogFiles logs;

    public CatalogApiService(CatalogProvider catalogs, LogFiles logs) {
        this.catalogs = catalogs;
        this.logs = logs;
    }

    public ApiDtos.PluginCatalogDto search(String query, int page, boolean refresh) {
        try {
            PluginCatalogService.CatalogPage result = catalogs.get().search(query, page, refresh);
            logs.append("插件目录查询：" + (query == null || query.isBlank() ? "（空）" : LogSanitizer.oneLine(query, 60))
                    + " 第 " + result.page() + " 页，共 " + result.total() + " 条"
                    + (result.fromCache() ? "（缓存）" : ""));
            return page(result);
        } catch (IllegalArgumentException failure) {
            throw ApiException.validation(failure.getMessage());
        } catch (IOException failure) {
            throw upstreamFailure(failure);
        } catch (IllegalStateException failure) {
            throw ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY,
                    LogSanitizer.oneLine(failure.getMessage(), 240));
        }
    }

    public ApiDtos.PluginDetailsDto resolve(String owner, String repo, boolean refresh) {
        String full = (owner == null ? "" : owner.trim()) + "/" + (repo == null ? "" : repo.trim());
        try {
            PluginCatalogService.PluginDetails details = catalogs.get().resolve(full, refresh);
            if (details.status() == PluginCatalogService.Status.ERROR) {
                // core 对网络类错误返回 ERROR 详情而不是抛出；按方案转换为中文 502/504。
                throw upstreamFailure(details.message());
            }
            return details(details);
        } catch (IllegalArgumentException failure) {
            throw ApiException.validation(failure.getMessage());
        } catch (IOException failure) {
            throw upstreamFailure(failure);
        } catch (IllegalStateException failure) {
            throw ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY,
                    LogSanitizer.oneLine(failure.getMessage(), 240));
        }
    }

    private static ApiDtos.PluginCatalogDto page(PluginCatalogService.CatalogPage result) {
        return new ApiDtos.PluginCatalogDto(
                result.entries().stream().map(CatalogApiService::entry).toList(),
                result.total(), result.page(), result.hasNext(), result.fromCache(), result.warning());
    }

    private static ApiDtos.PluginCatalogEntryDto entry(PluginCatalogService.CatalogEntry entry) {
        return new ApiDtos.PluginCatalogEntryDto(entry.repo(), entry.name(), entry.description(),
                entry.stars(), entry.url(), entry.spec(), entry.status().name());
    }

    private static ApiDtos.PluginDetailsDto details(PluginCatalogService.PluginDetails details) {
        List<ApiDtos.InstallTargetDto> targets = details.targets().stream()
                .map(target -> new ApiDtos.InstallTargetDto(target.name(), target.version(), target.spec(),
                        target.packagePath(), target.evidenceUrl()))
                .toList();
        return new ApiDtos.PluginDetailsDto(details.repo(), details.status().name(), details.message(),
                details.fromCache(), targets);
    }

    private ApiException upstreamFailure(IOException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        return upstreamFailure(message);
    }

    private ApiException upstreamFailure(String message) {
        String value = message == null ? "" : message.strip();
        if (value.contains("超时")) {
            return ApiException.upstream("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                    value.isEmpty() ? "插件目录查询超时，请稍后重试。" : value);
        }
        if (value.contains("取消")) {
            return ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, "插件目录查询已取消，请重试。");
        }
        if (value.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF)) {
            return ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, value);
        }
        log.warn("插件目录查询失败：{}", value);
        return ApiException.upstream("UPSTREAM_ERROR", HttpStatus.BAD_GATEWAY, GENERIC_FAILURE);
    }
}
