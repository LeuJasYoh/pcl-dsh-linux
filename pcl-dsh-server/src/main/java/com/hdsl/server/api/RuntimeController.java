package com.hdsl.server.api;

import com.hdsl.server.state.StateAssembler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/** 已安装运行时列表（版本查询/安装/探测属于后续增量）。 */
@RestController
@RequestMapping("/api")
public class RuntimeController {

    private final StateAssembler state;

    public RuntimeController(StateAssembler state) {
        this.state = state;
    }

    @GetMapping("/runtimes")
    public List<ApiDtos.RuntimeDto> runtimes() {
        try {
            return state.runtimes();
        } catch (IOException e) {
            throw ApiException.internal("读取已安装运行时失败：" + e.getMessage());
        }
    }
}
