package com.hdsl.server.api;

import com.hdsl.server.state.StateAssembler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/state：前端启动时的一次性聚合快照。 */
@RestController
@RequestMapping("/api")
public class StateController {

    private final StateAssembler state;

    public StateController(StateAssembler state) {
        this.state = state;
    }

    @GetMapping("/state")
    public ApiDtos.StateDto state() {
        return state.state();
    }
}
