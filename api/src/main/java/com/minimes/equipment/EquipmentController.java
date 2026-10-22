package com.minimes.equipment;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minimes.equipment.EquipmentService.EquipmentView;
import com.minimes.equipment.EquipmentService.StatusRequest;
import com.minimes.equipment.EquipmentService.StatusResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<EquipmentView> list() {
        return service.list();
    }

    @PostMapping("/{equipmentCode}/status")
    public StatusResponse changeStatus(@PathVariable String equipmentCode, @Valid @RequestBody StatusRequest req) {
        return service.changeStatus(equipmentCode, req);
    }
}
