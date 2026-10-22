package com.hdsl.server.api;

import com.hdsl.server.state.StateAssembler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 实例读取接口（M1 仅列表；CRUD/复制/启停属于后续增量）。 */
@RestController
@RequestMapping("/api")
public class InstanceController {

    private final StateAssembler state;

    public InstanceController(StateAssembler state) {
        this.state = state;
    }

    @GetMapping("/instances")
    public List<ApiDtos.InstanceDto> instances() {
        return state.instances();
    }
}
