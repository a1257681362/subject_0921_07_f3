package com.evops.machine.controller;

import com.evops.common.ApiResponse;
import com.evops.machine.MachineService;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.dto.MachineDisableRequest;
import com.evops.machine.dto.MachineUpdateRequest;
import com.evops.machine.entity.Machine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/machines")
public class MachineController {

    private final MachineService machineService;

    public MachineController(MachineService machineService) {
        this.machineService = machineService;
    }

    @PostMapping
    public ApiResponse<Machine> create(@Valid @RequestBody MachineCreateRequest request) {
        return ApiResponse.ok(machineService.create(request));
    }

    @PostMapping("/{id}")
    public ApiResponse<Machine> update(@PathVariable Long id,
                                       @Valid @RequestBody MachineUpdateRequest request) {
        return ApiResponse.ok(machineService.update(id, request));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Machine> disable(@PathVariable Long id,
                                        @Valid @RequestBody MachineDisableRequest request) {
        return ApiResponse.ok(machineService.disable(id, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<Machine> get(@PathVariable Long id) {
        return ApiResponse.ok(machineService.get(id));
    }

    @GetMapping
    public ApiResponse<List<Machine>> list() {
        return ApiResponse.ok(machineService.list());
    }
}
